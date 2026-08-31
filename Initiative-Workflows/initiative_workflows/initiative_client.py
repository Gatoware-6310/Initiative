from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass

import aiohttp


class InitiativeError(RuntimeError):
    pass


@dataclass(frozen=True)
class ActionSpec:
    name: str
    arguments: tuple[str, ...]


class InitiativeClient:
    def __init__(
        self,
        base_url: str,
        event_sink: Callable[[str, str], None],
        *,
        timeout: float = 8.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.event_sink = event_sink
        self.timeout = timeout
        self.connected = False
        self.devices: dict[str, str] = {}
        self.actions: dict[str, dict[str, ActionSpec]] = {}
        self._session: aiohttp.ClientSession | None = None
        self._action_locks: dict[str, asyncio.Lock] = {}

    async def start(self) -> None:
        if self._session is None:
            timeout = aiohttp.ClientTimeout(total=self.timeout)
            self._session = aiohttp.ClientSession(timeout=timeout)
        try:
            await self.refresh_devices()
        except InitiativeError:
            pass

    async def close(self) -> None:
        if self._session is not None:
            await self._session.close()
            self._session = None

    def _connection_ok(self) -> None:
        if not self.connected:
            self.connected = True
            self.event_sink("info", "Initiative reconnected")

    def _connection_failed(self) -> None:
        if self.connected:
            self.connected = False
            self.event_sink("warning", "Initiative disconnected")

    async def _request(self, path: str, body: str | None = None) -> str:
        if self._session is None:
            raise InitiativeError("Initiative client is not started")
        method = "POST" if body is not None else "GET"
        try:
            async with self._session.request(method, self.base_url + path, data=body) as response:
                text = await response.text()
                if response.status >= 400:
                    raise InitiativeError(
                        f"Initiative returned HTTP {response.status}: {text.strip() or 'no response'}"
                    )
                self._connection_ok()
                return text
        except InitiativeError:
            raise
        except (TimeoutError, aiohttp.ClientError) as error:
            self._connection_failed()
            raise InitiativeError(f"Could not reach Initiative Core: {error}") from error

    async def refresh_devices(self) -> dict[str, str]:
        response = await self._request("/initiative/devices")
        devices: dict[str, str] = {}
        for raw_line in response.splitlines():
            line = raw_line.strip()
            if not line or ":" not in line:
                continue
            name, device_type = line.rsplit(":", 1)
            devices[name] = device_type
        self.devices = devices
        return dict(devices)

    async def check_health(self) -> bool:
        await self._request("/initiative/health")
        return True

    async def get_status(self, device_name: str) -> str:
        return (await self._request("/initiative/devices/status", device_name)).strip()

    async def get_actions(self, device_name: str, *, refresh: bool = False) -> dict[str, ActionSpec]:
        if not refresh and device_name in self.actions:
            return self.actions[device_name]
        lock = self._action_locks.setdefault(device_name, asyncio.Lock())
        async with lock:
            if not refresh and device_name in self.actions:
                return self.actions[device_name]
            response = await self._request("/initiative/devices/actions", device_name)
            actions: dict[str, ActionSpec] = {}
            for raw_line in response.splitlines():
                fields = raw_line.strip().split("\t")
                if fields and fields[0]:
                    actions[fields[0]] = ActionSpec(fields[0], tuple(fields[1:]))
            self.actions[device_name] = actions
            return actions

    async def run_action(self, device_name: str, action_name: str, arguments: tuple[object, ...]) -> str:
        actions = await self.get_actions(device_name)
        action = actions.get(action_name)
        if action is None:
            actions = await self.get_actions(device_name, refresh=True)
            action = actions.get(action_name)
        if action is None:
            raise InitiativeError(f"Device '{device_name}' has no action named '{action_name}'")
        if len(arguments) != len(action.arguments):
            raise InitiativeError(
                f"{device_name}.{action_name} expects {len(action.arguments)} argument(s), got {len(arguments)}"
            )
        body = "\n".join((device_name, action_name, *(str(value) for value in arguments)))
        return (await self._request("/initiative/devices/execute", body)).strip()
