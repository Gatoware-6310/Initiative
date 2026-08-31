from __future__ import annotations

import asyncio
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

from aiohttp import ClientSession, web

from initiative_workflows.engine import WorkflowEngine
from initiative_workflows.initiative_client import InitiativeError
from initiative_workflows.scheduler import AsyncScheduler, Clock
from initiative_workflows.storage import WorkflowStorage
from initiative_workflows.web import create_app

DAILY_SCRIPT = """
local fan = initiative.device("Fan")
local function off() fan:run("Off") end
initiative.every_day("06:00", off)
initiative.on_run(off)
"""

INACTIVITY_SCRIPT = """
local fan = initiative.device("Fan")
local motion = initiative.device("Motion Sensor")
initiative.when_inactive(
    motion, "SECONDS SINCE MOTION", 1800, "12:00", "21:00",
    function() fan:run("Off") end
)
"""


class FakeClock(Clock):
    def __init__(self, current: datetime) -> None:
        self.current = current

    def timestamp(self) -> float:
        return self.current.timestamp()

    def local_datetime(self) -> datetime:
        return self.current

    def set(self, hour: int, minute: int, second: int = 0, *, days: int = 0) -> None:
        self.current = (self.current + timedelta(days=days)).replace(
            hour=hour, minute=minute, second=second, microsecond=0
        )


class FakeClient:
    def __init__(self) -> None:
        self.base_url = "http://initiative.test:1231"
        self.connected = False
        self.available = True
        self.devices = {"Fan": "node", "Motion Sensor": "node"}
        self.statuses = {"Motion Sensor": "SECONDS SINCE MOTION: 0"}
        self.actions: list[tuple[str, str, tuple[object, ...]]] = []
        self.start_count = 0
        self.health_count = 0

    async def start(self) -> None:
        self.start_count += 1
        self.connected = self.available

    async def close(self) -> None:
        self.connected = False

    async def get_status(self, device_name: str) -> str:
        if not self.available:
            self.connected = False
            raise InitiativeError("Core unavailable")
        self.connected = True
        return self.statuses[device_name]

    async def check_health(self) -> bool:
        self.health_count += 1
        if not self.available:
            self.connected = False
            raise InitiativeError("Core unavailable")
        self.connected = True
        return True

    async def run_action(self, device_name: str, action_name: str, arguments: tuple[object, ...]) -> str:
        if not self.available:
            self.connected = False
            raise InitiativeError("Core unavailable")
        self.connected = True
        self.actions.append((device_name, action_name, arguments))
        return "ok"


class EngineCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.engines: list[WorkflowEngine] = []

    async def asyncTearDown(self) -> None:
        for engine in reversed(self.engines):
            await engine.close()
        self.temporary.cleanup()

    async def make_engine(
        self,
        scripts: list[tuple[str, str, bool]],
        *,
        clock: FakeClock | None = None,
        client: FakeClient | None = None,
        existing: bool = False,
    ) -> tuple[WorkflowEngine, FakeClock, FakeClient]:
        storage = WorkflowStorage(self.directory)
        if not existing:
            for name, script, enabled in scripts:
                storage.create(name, script, enabled)
        clock = clock or FakeClock(datetime(2026, 8, 30, 5, 59, tzinfo=UTC))
        client = client or FakeClient()
        engine = WorkflowEngine(
            storage,
            client=client,
            scheduler=AsyncScheduler(clock),
            poll_interval=15,
        )
        self.engines.append(engine)
        await engine.start(background=False)
        return engine, clock, client

    async def settle(self, engine: WorkflowEngine) -> None:
        await engine.scheduler.drain()
        await asyncio.sleep(0)
        await asyncio.sleep(0)

    async def test_lua_workflow_loads_and_manual_run_executes_action(self) -> None:
        engine, _, client = await self.make_engine([("Fan Morning", DAILY_SCRIPT, True)])
        instance = engine.get("fan-morning")
        self.assertEqual("enabled", instance.state)
        await engine.run("fan-morning")
        await self.settle(engine)
        self.assertEqual([("Fan", "Off", ())], client.actions)

    async def test_lua_syntax_error_is_isolated(self) -> None:
        engine, _, _ = await self.make_engine(
            [("Broken", "this is not lua", True), ("Fan Morning", DAILY_SCRIPT, True)]
        )
        self.assertEqual("error", engine.get("broken").state)
        self.assertIn("syntax", engine.get("broken").last_error.lower())
        self.assertEqual("enabled", engine.get("fan-morning").state)

    async def test_lua_cannot_use_os_or_python_attributes(self) -> None:
        unsafe = "return os.execute('id')"
        engine, _, _ = await self.make_engine([("Unsafe", unsafe, True)])
        self.assertEqual("error", engine.get("unsafe").state)
        self.assertIn("nil value", engine.get("unsafe").last_error)

    async def test_daily_schedule_fires_once_and_restart_does_not_repeat(self) -> None:
        engine, clock, client = await self.make_engine([("Fan Morning", DAILY_SCRIPT, True)])
        clock.set(6, 0)
        await engine.scheduler.run_due()
        await self.settle(engine)
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, len(client.actions))

        await engine.close()
        self.engines.remove(engine)
        clock.set(6, 1)
        restarted_client = FakeClient()
        restarted, _, _ = await self.make_engine([], clock=clock, client=restarted_client, existing=True)
        await restarted.scheduler.run_due()
        await self.settle(restarted)
        self.assertEqual([], restarted_client.actions)

    async def test_reload_replaces_instead_of_duplicating_timer(self) -> None:
        engine, _, _ = await self.make_engine([("Fan Morning", DAILY_SCRIPT, True)])
        instance = engine.get("fan-morning")
        old_owner = instance.context.owner
        self.assertEqual(1, engine.scheduler.pending_for(old_owner))
        await engine.reload("fan-morning")
        new_owner = instance.context.owner
        self.assertNotEqual(old_owner, new_owner)
        self.assertEqual(0, engine.scheduler.pending_for(old_owner))
        self.assertEqual(1, engine.scheduler.pending_for(new_owner))

    async def test_inactivity_fires_once_rearms_on_motion_and_fires_again(self) -> None:
        clock = FakeClock(datetime(2026, 8, 30, 13, 0, tzinfo=UTC))
        client = FakeClient()
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 1800"
        engine, _, _ = await self.make_engine([("Fan Inactivity", INACTIVITY_SCRIPT, True)], clock=clock, client=client)

        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, len(client.actions))

        clock.set(13, 10)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 2400"
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, len(client.actions))

        clock.set(13, 30)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 0"
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        clock.set(14, 0)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 1800"
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(2, len(client.actions))

    async def test_inactivity_waits_for_noon_and_does_not_fire_at_nine_pm(self) -> None:
        clock = FakeClock(datetime(2026, 8, 30, 11, 0, tzinfo=UTC))
        client = FakeClient()
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 1800"
        engine, _, _ = await self.make_engine([("Fan Inactivity", INACTIVITY_SCRIPT, True)], clock=clock, client=client)
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual([], client.actions)

        clock.set(12, 0)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 5400"
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, len(client.actions))

        clock.set(20, 30)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 0"
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        clock.set(21, 0)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 1800"
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, len(client.actions))

    async def test_disabling_cancels_timers_and_callbacks(self) -> None:
        script = DAILY_SCRIPT + "\n" + INACTIVITY_SCRIPT
        engine, clock, client = await self.make_engine([("Fan Rules", script, True)])
        instance = engine.get("fan-rules")
        owner = instance.context.owner
        self.assertGreater(engine.scheduler.pending_for(owner), 0)
        await engine.disable("fan-rules")
        self.assertEqual(0, engine.scheduler.pending_for(owner))
        clock.set(6, 0)
        await engine.status_hub.publish("Motion Sensor", "SECONDS SINCE MOTION: 1800")
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual([], client.actions)

    async def test_core_reconnect_retry_keeps_one_timer_and_one_workflow(self) -> None:
        clock = FakeClock(datetime(2026, 8, 30, 13, 0, tzinfo=UTC))
        client = FakeClient()
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 1800"
        engine, _, _ = await self.make_engine([("Fan Inactivity", INACTIVITY_SCRIPT, True)], clock=clock, client=client)
        instance = engine.get("fan-inactivity")
        owner = instance.context.owner
        generation = instance.generation
        await engine.status_hub.publish("Motion Sensor", client.statuses["Motion Sensor"])
        client.available = False
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, engine.scheduler.pending_for(owner))
        self.assertEqual(generation, instance.generation)

        client.available = True
        clock.current += timedelta(seconds=15)
        client.statuses["Motion Sensor"] = "SECONDS SINCE MOTION: 1815"
        await engine.scheduler.run_due()
        await self.settle(engine)
        self.assertEqual(1, len(client.actions))
        self.assertEqual(generation, instance.generation)

    async def test_health_monitor_reconnects_without_reloading_daily_workflow(
        self,
    ) -> None:
        client = FakeClient()
        client.available = False
        engine, _, _ = await self.make_engine([("Fan Morning", DAILY_SCRIPT, True)], client=client)
        instance = engine.get("fan-morning")
        generation = instance.generation
        owner = instance.context.owner
        engine.status_hub.poll_interval = 0.01
        engine.status_hub.start()
        client.available = True
        await asyncio.sleep(0.03)
        self.assertTrue(client.connected)
        self.assertGreaterEqual(client.health_count, 1)
        self.assertEqual(generation, instance.generation)
        self.assertEqual(1, engine.scheduler.pending_for(owner))

    async def test_frontend_and_json_api_load_on_an_http_port(self) -> None:
        engine, _, _ = await self.make_engine([("Fan Morning", DAILY_SCRIPT, False)])
        static_directory = Path(__file__).resolve().parents[1] / "static"
        runner = web.AppRunner(create_app(engine, static_directory))
        await runner.setup()
        site = web.TCPSite(runner, "127.0.0.1", 0)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        try:
            async with ClientSession() as session:
                async with session.get(f"http://127.0.0.1:{port}/") as response:
                    html = await response.text()
                    self.assertEqual(200, response.status)
                    self.assertIn("Initiative Workflows", html)
                async with session.get(f"http://127.0.0.1:{port}/api/workflows") as response:
                    data = await response.json()
                    self.assertEqual("fan-morning", data["workflows"][0]["id"])
        finally:
            await runner.cleanup()


if __name__ == "__main__":
    unittest.main()
