from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
from pathlib import Path

from aiohttp import web

from .engine import WorkflowEngine
from .storage import WorkflowStorage
from .web import create_app

PROGRAM_DIRECTORY = Path(__file__).resolve().parent.parent


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Initiative Workflows")
    parser.add_argument("--host", default=os.environ.get("WORKFLOWS_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("WORKFLOWS_PORT", "1235")))
    parser.add_argument(
        "--core-url",
        default=os.environ.get("INITIATIVE_CORE_URL", "http://10.0.0.17:1231"),
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(os.environ.get("WORKFLOWS_DATA_DIR", PROGRAM_DIRECTORY / "workflows")),
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        default=float(os.environ.get("WORKFLOWS_POLL_INTERVAL", "15")),
    )
    return parser.parse_args()


async def serve(options: argparse.Namespace) -> None:
    storage = WorkflowStorage(options.data_dir)
    engine = WorkflowEngine(storage, core_url=options.core_url, poll_interval=options.poll_interval)
    app = create_app(engine, PROGRAM_DIRECTORY / "static")
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, options.host, options.port)
    await site.start()
    logging.getLogger("initiative_workflows").info(
        "Initiative Workflows listening on http://%s:%s", options.host, options.port
    )
    await engine.start()

    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(name, stopped.set)
        except NotImplementedError:
            pass
    try:
        await stopped.wait()
    finally:
        await runner.cleanup()
        await engine.close()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        asyncio.run(serve(arguments()))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
