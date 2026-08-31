from __future__ import annotations

import asyncio
import logging
import re
from collections import defaultdict, deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from .initiative_client import InitiativeClient, InitiativeError
from .lua_runtime import LuaSandboxError, LuaWorkflow
from .scheduler import AsyncScheduler
from .storage import WorkflowRecord, WorkflowStorage

LOGGER = logging.getLogger("initiative_workflows")
StatusCallback = Callable[[str], Awaitable[None]]


@dataclass
class LogEntry:
    timestamp: str
    level: str
    message: str


@dataclass
class WorkflowInstance:
    record: WorkflowRecord
    generation: int = 0
    context: WorkflowContext | None = None
    state: str = "disabled"
    running: int = 0
    last_error: str | None = None
    logs: deque[LogEntry] = field(default_factory=lambda: deque(maxlen=100))


class StatusHub:
    def __init__(self, client: InitiativeClient, *, poll_interval: float = 15.0) -> None:
        self.client = client
        self.poll_interval = poll_interval
        self.cache: dict[str, str] = {}
        self._subscribers: dict[str, dict[str, list[StatusCallback]]] = defaultdict(lambda: defaultdict(list))
        self._wake = asyncio.Event()
        self._task: asyncio.Task[None] | None = None
        self._refresh_locks: dict[str, asyncio.Lock] = {}

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._poll(), name="initiative-status-poller")

    def subscribe(self, device_name: str, owner: str, callback: StatusCallback) -> None:
        self._subscribers[device_name][owner].append(callback)
        self._wake.set()

    def unsubscribe_owner(self, owner: str) -> None:
        for device_name in list(self._subscribers):
            self._subscribers[device_name].pop(owner, None)
            if not self._subscribers[device_name]:
                del self._subscribers[device_name]
        self._wake.set()

    async def refresh(self, device_name: str, *, notify: bool = True) -> str:
        lock = self._refresh_locks.setdefault(device_name, asyncio.Lock())
        async with lock:
            status = await self.client.get_status(device_name)
        changed = self.cache.get(device_name) != status
        self.cache[device_name] = status
        if notify and changed:
            callbacks = [
                callback for callbacks in self._subscribers.get(device_name, {}).values() for callback in callbacks
            ]
            if callbacks:
                await asyncio.gather(
                    *(callback(status) for callback in callbacks),
                    return_exceptions=True,
                )
        return status

    async def publish(self, device_name: str, status: str) -> None:
        changed = self.cache.get(device_name) != status
        self.cache[device_name] = status
        if not changed:
            return
        callbacks = [
            callback for callbacks in self._subscribers.get(device_name, {}).values() for callback in callbacks
        ]
        if callbacks:
            await asyncio.gather(*(callback(status) for callback in callbacks), return_exceptions=True)

    async def _poll(self) -> None:
        try:
            while True:
                devices = tuple(self._subscribers)
                if devices:
                    await asyncio.gather(
                        *(self.refresh(device) for device in devices),
                        return_exceptions=True,
                    )
                else:
                    try:
                        await self.client.check_health()
                    except InitiativeError:
                        pass
                self._wake.clear()
                try:
                    await asyncio.wait_for(self._wake.wait(), timeout=self.poll_interval)
                except TimeoutError:
                    pass
        except asyncio.CancelledError:
            pass

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
            self._task = None
        self._subscribers.clear()


def parse_time(value: str) -> tuple[int, int]:
    match = re.fullmatch(r"\s*(\d{1,2}):(\d{2})\s*", value)
    if not match:
        raise ValueError(f"Invalid time '{value}'; expected HH:MM")
    hour, minute = int(match.group(1)), int(match.group(2))
    if hour > 23 or minute > 59:
        raise ValueError(f"Invalid time '{value}'; expected HH:MM")
    return hour, minute


def status_number(status: str, key: str) -> float | None:
    match = re.search(
        re.escape(key) + r"\s*[:=]\s*(-?(?:\d+(?:\.\d*)?|\.\d+))",
        status,
        flags=re.IGNORECASE,
    )
    return float(match.group(1)) if match else None


class DailyRegistration:
    def __init__(
        self,
        context: WorkflowContext,
        state_key: str,
        time_text: str,
        callback: Any,
        *,
        catchup_seconds: float = 300.0,
    ) -> None:
        self.context = context
        self.state_key = state_key
        self.hour, self.minute = parse_time(time_text)
        self.callback = callback
        self.catchup_seconds = catchup_seconds
        self.timer_key = f"timer:{state_key}"
        self.schedule_initial()

    def _occurrence(self, now: datetime, days: int = 0) -> datetime:
        date = (now + timedelta(days=days)).date()
        return datetime.combine(
            date,
            now.timetz().replace(hour=self.hour, minute=self.minute, second=0, microsecond=0),
        )

    def schedule_initial(self) -> None:
        now = self.context.clock.local_datetime()
        occurrence = self._occurrence(now)
        last_fired = self.context.get_state(self.state_key)
        if occurrence > now:
            target = occurrence.timestamp()
        elif (now - occurrence).total_seconds() <= self.catchup_seconds and last_fired != occurrence.date().isoformat():
            target = self.context.clock.timestamp()
        else:
            target = self._occurrence(now, 1).timestamp()
        self.context.scheduler.call_at(target, self._fire, owner=self.context.owner, key=self.timer_key)

    async def _fire(self) -> None:
        now = self.context.clock.local_datetime()
        today = now.date().isoformat()
        if self.context.get_state(self.state_key) != today:
            self.context.set_state(self.state_key, today)
            await self.context.invoke(self.callback, "scheduled")
        next_occurrence = self._occurrence(now, 1)
        self.context.scheduler.call_at(
            next_occurrence.timestamp(),
            self._fire,
            owner=self.context.owner,
            key=self.timer_key,
        )


class InactivityRegistration:
    def __init__(
        self,
        context: WorkflowContext,
        state_key: str,
        device_name: str,
        status_key: str,
        seconds: float,
        start_time: str,
        end_time: str,
        callback: Any,
    ) -> None:
        if seconds <= 0:
            raise ValueError("Inactivity seconds must be greater than zero")
        self.context = context
        self.state_key = state_key
        self.device_name = device_name
        self.status_key = status_key
        self.seconds = float(seconds)
        self.start = parse_time(start_time)
        self.end = parse_time(end_time)
        if self.start >= self.end:
            raise ValueError("The inactivity time window must start before it ends")
        self.callback = callback
        self.timer_key = f"timer:{state_key}"
        self.motion_epoch: float | None = None
        persisted = self.context.get_state(state_key)
        self.fired_motion_epoch = float(persisted) if isinstance(persisted, (int, float)) else None
        self.fired = False
        self.scheduled_when: float | None = None
        self.tolerance = max(5.0, self.context.status_hub.poll_interval * 2.0)
        self.context.status_hub.subscribe(device_name, self.context.owner, self.observe)

    def _parse(self, status: str) -> float | None:
        value = status_number(status, self.status_key)
        if value is None or value < 0:
            return None
        return value

    def _update_period(self, observed_seconds: float, now_epoch: float) -> None:
        estimated_motion = now_epoch - observed_seconds
        if self.motion_epoch is None:
            self.motion_epoch = estimated_motion
            self.fired = (
                self.fired_motion_epoch is not None
                and abs(self.fired_motion_epoch - estimated_motion) <= self.tolerance
            )
            if self.fired_motion_epoch is not None and not self.fired:
                self.context.set_state(self.state_key, None)
                self.fired_motion_epoch = None
            return
        if estimated_motion > self.motion_epoch + self.tolerance:
            self.motion_epoch = estimated_motion
            self.fired = False
            self.fired_motion_epoch = None
            self.context.set_state(self.state_key, None)
        elif abs(estimated_motion - self.motion_epoch) <= self.tolerance:
            self.motion_epoch = (self.motion_epoch + estimated_motion) / 2.0

    def _inside_window(self, moment: datetime) -> bool:
        current = (moment.hour, moment.minute)
        return self.start <= current < self.end

    def _eligible_timestamp(self, due: float) -> float:
        moment = datetime.fromtimestamp(due, tz=self.context.clock.local_datetime().tzinfo)
        current = (moment.hour, moment.minute)
        if self.start <= current < self.end:
            return due
        if current < self.start:
            eligible = moment.replace(hour=self.start[0], minute=self.start[1], second=0, microsecond=0)
        else:
            eligible = (moment + timedelta(days=1)).replace(
                hour=self.start[0], minute=self.start[1], second=0, microsecond=0
            )
        return eligible.timestamp()

    def _schedule(self, when: float) -> None:
        if self.fired:
            self.context.scheduler.cancel(self.context.owner, self.timer_key)
            self.scheduled_when = None
            return
        if self.scheduled_when is not None and abs(self.scheduled_when - when) <= self.tolerance:
            return
        self.scheduled_when = when
        self.context.scheduler.call_at(when, self._due, owner=self.context.owner, key=self.timer_key)

    async def observe(self, status: str) -> None:
        observed_seconds = self._parse(status)
        if observed_seconds is None:
            self.context.error_once(
                f"status:{self.state_key}",
                f"Could not read '{self.status_key}' from {self.device_name} status: {status!r}",
            )
            return
        self.context.clear_error_once(f"status:{self.state_key}")
        now = self.context.clock.timestamp()
        self._update_period(observed_seconds, now)
        if self.fired:
            self._schedule(now)
            return
        deadline = now + max(0.0, self.seconds - observed_seconds)
        self._schedule(self._eligible_timestamp(deadline))

    async def _due(self) -> None:
        self.scheduled_when = None
        try:
            status = await self.context.status_hub.refresh(self.device_name, notify=False)
        except InitiativeError:
            self._schedule(self.context.clock.timestamp() + self.context.status_hub.poll_interval)
            return
        observed_seconds = self._parse(status)
        if observed_seconds is None:
            await self.observe(status)
            return
        now_epoch = self.context.clock.timestamp()
        self._update_period(observed_seconds, now_epoch)
        if self.fired:
            return
        now = self.context.clock.local_datetime()
        if observed_seconds < self.seconds:
            self._schedule(self._eligible_timestamp(now_epoch + self.seconds - observed_seconds))
            return
        if not self._inside_window(now):
            self._schedule(self._eligible_timestamp(now_epoch))
            return
        if self.motion_epoch is None:
            self.motion_epoch = now_epoch - observed_seconds
        self.fired = True
        self.fired_motion_epoch = self.motion_epoch
        self.context.set_state(self.state_key, self.motion_epoch)
        await self.context.invoke(self.callback, "inactivity")


class WorkflowContext:
    def __init__(self, engine: WorkflowEngine, instance: WorkflowInstance, owner: str) -> None:
        self.engine = engine
        self.instance = instance
        self.owner = owner
        self.scheduler = engine.scheduler
        self.clock = engine.scheduler.clock
        self.status_hub = engine.status_hub
        self.client = engine.client
        self.runtime: LuaWorkflow | None = None
        self.manual_callbacks: list[Any] = []
        self.tasks: set[asyncio.Task[Any]] = set()
        self._registration_counts: dict[str, int] = defaultdict(int)
        self._reported_errors: set[str] = set()
        self.closed = False

    def _next_key(self, kind: str, detail: str) -> str:
        self._registration_counts[kind] += 1
        return f"{kind}:{self._registration_counts[kind]}:{detail}"

    def get_state(self, key: str) -> object | None:
        return self.instance.record.state.get(key)

    def set_state(self, key: str, value: object | None) -> None:
        self.engine.storage.set_state(self.instance.record.workflow_id, key, value)
        if value is None:
            self.instance.record.state.pop(key, None)
        else:
            self.instance.record.state[key] = value

    def device_status(self, device_name: str) -> str | None:
        return self.status_hub.cache.get(device_name)

    def queue_action(self, device_name: str, action_name: str, arguments: tuple[object, ...]) -> bool:
        if self.closed:
            return False
        allowed = (str, int, float, bool)
        if any(value is not None and not isinstance(value, allowed) for value in arguments):
            raise ValueError("Initiative action arguments must be strings, numbers, or booleans")
        task = asyncio.create_task(
            self.engine._run_action(self, device_name, action_name, arguments),
            name=f"workflow-action-{self.instance.record.workflow_id}",
        )
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return True

    def register_daily(self, time_text: str, callback: Any) -> None:
        key = self._next_key("daily", time_text)
        DailyRegistration(self, key, time_text, callback)

    def register_inactivity(
        self,
        device_name: str,
        status_key: str,
        seconds: float,
        start_time: str,
        end_time: str,
        callback: Any,
    ) -> None:
        detail = f"{device_name}:{status_key}:{seconds}:{start_time}:{end_time}"
        key = self._next_key("inactivity", detail)
        InactivityRegistration(self, key, device_name, status_key, seconds, start_time, end_time, callback)

    def register_status(self, device_name: str, callback: Any) -> None:
        async def status_changed(status: str) -> None:
            await self.invoke(callback, "status", status, announce=False)

        self.status_hub.subscribe(device_name, self.owner, status_changed)

    def register_manual(self, callback: Any) -> None:
        self.manual_callbacks.append(callback)

    def log(self, message: str) -> None:
        self.engine._log(self.instance, "info", message, terminal=False)

    def error_once(self, key: str, message: str) -> None:
        if key not in self._reported_errors:
            self._reported_errors.add(key)
            self.engine._log(self.instance, "error", message)

    def clear_error_once(self, key: str) -> None:
        self._reported_errors.discard(key)

    async def invoke(self, callback: Any, trigger: str, *arguments: object, announce: bool = True) -> None:
        if self.closed or self.runtime is None:
            return
        self.instance.running += 1
        if announce:
            self.engine._log(
                self.instance,
                "info",
                f"Workflow triggered: {self.instance.record.name} ({trigger})",
            )
        try:
            self.runtime.call(callback, *arguments)
        except LuaSandboxError as error:
            self.instance.last_error = str(error)
            self.instance.state = "error"
            self.engine._log(
                self.instance,
                "error",
                f"Lua error in {self.instance.record.name}: {error}",
            )
        finally:
            self.instance.running -= 1

    async def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        self.scheduler.cancel_owner(self.owner)
        self.status_hub.unsubscribe_owner(self.owner)
        for task in tuple(self.tasks):
            task.cancel()
        if self.tasks:
            await asyncio.gather(*tuple(self.tasks), return_exceptions=True)
        self.tasks.clear()
        self.manual_callbacks.clear()
        self.runtime = None


class WorkflowEngine:
    def __init__(
        self,
        storage: WorkflowStorage,
        *,
        core_url: str = "http://10.0.0.17:1231",
        client: InitiativeClient | None = None,
        scheduler: AsyncScheduler | None = None,
        poll_interval: float = 15.0,
    ) -> None:
        self.storage = storage
        self.scheduler = scheduler or AsyncScheduler()
        self.system_logs: deque[LogEntry] = deque(maxlen=100)
        self.client = client or InitiativeClient(core_url, self._system_event)
        self.status_hub = StatusHub(self.client, poll_interval=poll_interval)
        self.instances: dict[str, WorkflowInstance] = {}

    def _timestamp(self) -> str:
        return self.scheduler.clock.local_datetime().isoformat(timespec="seconds")

    def _system_event(self, level: str, message: str) -> None:
        self.system_logs.append(LogEntry(self._timestamp(), level, message))
        getattr(LOGGER, level if hasattr(LOGGER, level) else "info")(message)

    def _log(
        self,
        instance: WorkflowInstance,
        level: str,
        message: str,
        *,
        terminal: bool = True,
    ) -> None:
        instance.logs.append(LogEntry(self._timestamp(), level, message))
        if terminal:
            getattr(LOGGER, level if hasattr(LOGGER, level) else "info")(message)

    async def start(self, *, background: bool = True) -> None:
        if background:
            self.scheduler.start()
        await self.client.start()
        if background:
            self.status_hub.start()
        for record in self.storage.list():
            instance = WorkflowInstance(record=record)
            self.instances[record.workflow_id] = instance
            if record.enabled:
                await self._activate(instance)

    async def close(self) -> None:
        for instance in tuple(self.instances.values()):
            if instance.context is not None:
                await instance.context.close()
                instance.context = None
        await self.status_hub.close()
        await self.scheduler.close()
        await self.client.close()

    async def _activate(self, instance: WorkflowInstance) -> None:
        if instance.context is not None:
            await instance.context.close()
        instance.generation += 1
        instance.state = "loading"
        instance.last_error = None
        context = WorkflowContext(self, instance, f"{instance.record.workflow_id}:{instance.generation}")
        instance.context = context
        try:
            runtime = LuaWorkflow(context)
            context.runtime = runtime
            runtime.load(instance.record.script, f"{instance.record.workflow_id}.lua")
        except (LuaSandboxError, ValueError) as error:
            await context.close()
            instance.context = None
            instance.state = "error"
            instance.last_error = str(error)
            self._log(instance, "error", f"Lua error in {instance.record.name}: {error}")
            return
        instance.state = "enabled"
        self._log(instance, "info", f"Workflow enabled: {instance.record.name}")

    async def _run_action(
        self,
        context: WorkflowContext,
        device_name: str,
        action_name: str,
        arguments: tuple[object, ...],
    ) -> None:
        instance = context.instance
        instance.running += 1
        self._log(instance, "info", f"{instance.record.name} -> {device_name}.{action_name}")
        try:
            output = await self.client.run_action(device_name, action_name, arguments)
            if output and output != "ok":
                self._log(instance, "info", output, terminal=False)
        except (TimeoutError, InitiativeError) as error:
            instance.last_error = str(error)
            self._log(instance, "error", f"{device_name}.{action_name} failed: {error}")
        finally:
            instance.running -= 1

    def get(self, workflow_id: str) -> WorkflowInstance:
        try:
            return self.instances[workflow_id]
        except KeyError as error:
            raise KeyError(f"Workflow '{workflow_id}' does not exist") from error

    async def create(self, name: str, script: str, enabled: bool = False) -> WorkflowInstance:
        record = self.storage.create(name, script, enabled)
        instance = WorkflowInstance(record=record)
        self.instances[record.workflow_id] = instance
        if enabled:
            await self._activate(instance)
        return instance

    async def update(self, workflow_id: str, *, name: str | None = None, script: str | None = None) -> WorkflowInstance:
        instance = self.get(workflow_id)
        instance.record = self.storage.update(workflow_id, name=name, script=script)
        if instance.record.enabled:
            await self._activate(instance)
        return instance

    async def enable(self, workflow_id: str) -> WorkflowInstance:
        instance = self.get(workflow_id)
        instance.record = self.storage.set_enabled(workflow_id, True)
        await self._activate(instance)
        return instance

    async def disable(self, workflow_id: str) -> WorkflowInstance:
        instance = self.get(workflow_id)
        instance.record = self.storage.set_enabled(workflow_id, False)
        if instance.context is not None:
            await instance.context.close()
            instance.context = None
        instance.state = "disabled"
        instance.running = 0
        self._log(instance, "info", f"Workflow disabled: {instance.record.name}")
        return instance

    async def reload(self, workflow_id: str) -> WorkflowInstance:
        instance = self.get(workflow_id)
        instance.record = self.storage.get(workflow_id)
        if instance.record.enabled:
            await self._activate(instance)
        return instance

    async def run(self, workflow_id: str) -> WorkflowInstance:
        instance = self.get(workflow_id)
        if instance.context is None or instance.state == "error":
            raise ValueError("Enable and successfully load the workflow before running it")
        if not instance.context.manual_callbacks:
            self._log(
                instance,
                "info",
                "No initiative.on_run callback is registered",
                terminal=False,
            )
        for callback in tuple(instance.context.manual_callbacks):
            await instance.context.invoke(callback, "manual")
        return instance

    async def delete(self, workflow_id: str) -> None:
        instance = self.get(workflow_id)
        if instance.context is not None:
            await instance.context.close()
        self.storage.delete(workflow_id)
        del self.instances[workflow_id]

    def serialize(self, instance: WorkflowInstance, *, include_script: bool = False) -> dict[str, object]:
        data: dict[str, object] = {
            "id": instance.record.workflow_id,
            "name": instance.record.name,
            "enabled": instance.record.enabled,
            "state": instance.state,
            "running": instance.running > 0,
            "last_error": instance.last_error,
            "logs": [entry.__dict__ for entry in instance.logs],
            "updated_at": instance.record.updated_at,
        }
        if include_script:
            data["script"] = instance.record.script
        return data

    def service_status(self) -> dict[str, object]:
        return {
            "core_url": self.client.base_url,
            "core_connected": self.client.connected,
            "devices": self.client.devices,
            "logs": [entry.__dict__ for entry in self.system_logs],
        }
