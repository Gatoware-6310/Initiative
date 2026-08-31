from __future__ import annotations

import asyncio
import heapq
import inspect
import itertools
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime

TimerCallback = Callable[[], Awaitable[None] | None]


class Clock:
    def timestamp(self) -> float:
        return time.time()

    def local_datetime(self) -> datetime:
        return datetime.now().astimezone()


@dataclass
class TimerHandle:
    when: float
    owner: str
    key: str
    callback: TimerCallback
    active: bool = True

    def cancel(self) -> None:
        self.active = False


class AsyncScheduler:
    def __init__(self, clock: Clock | None = None) -> None:
        self.clock = clock or Clock()
        self._heap: list[tuple[float, int, TimerHandle]] = []
        self._handles: dict[tuple[str, str], TimerHandle] = {}
        self._sequence = itertools.count()
        self._wake = asyncio.Event()
        self._runner: asyncio.Task[None] | None = None
        self._callbacks: set[asyncio.Task[None]] = set()

    def start(self) -> None:
        if self._runner is None or self._runner.done():
            self._runner = asyncio.create_task(self._run(), name="initiative-workflow-scheduler")

    def call_at(self, when: float, callback: TimerCallback, *, owner: str, key: str) -> TimerHandle:
        existing = self._handles.pop((owner, key), None)
        if existing is not None:
            existing.cancel()
        handle = TimerHandle(when=when, owner=owner, key=key, callback=callback)
        self._handles[(owner, key)] = handle
        heapq.heappush(self._heap, (when, next(self._sequence), handle))
        self._wake.set()
        return handle

    def cancel(self, owner: str, key: str) -> None:
        handle = self._handles.pop((owner, key), None)
        if handle is not None:
            handle.cancel()
            self._wake.set()

    def cancel_owner(self, owner: str) -> None:
        for handle_key, handle in list(self._handles.items()):
            if handle.owner == owner:
                handle.cancel()
                del self._handles[handle_key]
        self._wake.set()

    def pending_for(self, owner: str) -> int:
        return sum(handle.active and handle.owner == owner for handle in self._handles.values())

    def _discard_stale(self) -> None:
        while self._heap and not self._heap[0][2].active:
            heapq.heappop(self._heap)

    async def run_due(self) -> None:
        now = self.clock.timestamp()
        self._discard_stale()
        while self._heap and self._heap[0][0] <= now:
            _, _, handle = heapq.heappop(self._heap)
            if not handle.active:
                continue
            handle.active = False
            self._handles.pop((handle.owner, handle.key), None)
            task = asyncio.create_task(self._invoke(handle.callback))
            self._callbacks.add(task)
            task.add_done_callback(self._callbacks.discard)
            self._discard_stale()

    async def _invoke(self, callback: TimerCallback) -> None:
        result = callback()
        if inspect.isawaitable(result):
            await result

    async def drain(self) -> None:
        while self._callbacks:
            await asyncio.gather(*tuple(self._callbacks), return_exceptions=True)

    async def _run(self) -> None:
        try:
            while True:
                await self.run_due()
                self._discard_stale()
                self._wake.clear()
                if not self._heap:
                    await self._wake.wait()
                    continue
                delay = max(0.0, self._heap[0][0] - self.clock.timestamp())
                try:
                    await asyncio.wait_for(self._wake.wait(), timeout=delay)
                except TimeoutError:
                    pass
        except asyncio.CancelledError:
            pass

    async def close(self) -> None:
        if self._runner is not None:
            self._runner.cancel()
            await asyncio.gather(self._runner, return_exceptions=True)
            self._runner = None
        for handle in self._handles.values():
            handle.cancel()
        self._handles.clear()
        for task in tuple(self._callbacks):
            task.cancel()
        if self._callbacks:
            await asyncio.gather(*tuple(self._callbacks), return_exceptions=True)
        self._callbacks.clear()
