from __future__ import annotations

from pathlib import Path
from typing import Any

from aiohttp import web

from .engine import WorkflowEngine


class WorkflowWeb:
    def __init__(self, engine: WorkflowEngine, static_directory: Path) -> None:
        self.engine = engine
        self.index_path = Path(static_directory) / "index.html"

    async def index(self, request: web.Request) -> web.StreamResponse:
        return web.FileResponse(self.index_path, headers={"Cache-Control": "no-store"})

    async def status(self, request: web.Request) -> web.Response:
        return web.json_response(self.engine.service_status())

    async def list_workflows(self, request: web.Request) -> web.Response:
        workflows = [
            self.engine.serialize(instance)
            for instance in sorted(
                self.engine.instances.values(),
                key=lambda item: item.record.name.lower(),
            )
        ]
        return web.json_response({"workflows": workflows})

    async def get_workflow(self, request: web.Request) -> web.Response:
        instance = self.engine.get(request.match_info["workflow_id"])
        return web.json_response(self.engine.serialize(instance, include_script=True))

    async def create_workflow(self, request: web.Request) -> web.Response:
        data = await self._json(request)
        name = data.get("name")
        script = data.get("script", "")
        if not isinstance(name, str) or not isinstance(script, str):
            raise web.HTTPBadRequest(text="Name and script must be strings")
        instance = await self.engine.create(name, script, bool(data.get("enabled", False)))
        return web.json_response(self.engine.serialize(instance, include_script=True), status=201)

    async def update_workflow(self, request: web.Request) -> web.Response:
        data = await self._json(request)
        name = data.get("name") if "name" in data else None
        script = data.get("script") if "script" in data else None
        if name is not None and not isinstance(name, str):
            raise web.HTTPBadRequest(text="Name must be a string")
        if script is not None and not isinstance(script, str):
            raise web.HTTPBadRequest(text="Script must be a string")
        instance = await self.engine.update(request.match_info["workflow_id"], name=name, script=script)
        return web.json_response(self.engine.serialize(instance, include_script=True))

    async def enable_workflow(self, request: web.Request) -> web.Response:
        instance = await self.engine.enable(request.match_info["workflow_id"])
        return web.json_response(self.engine.serialize(instance, include_script=True))

    async def disable_workflow(self, request: web.Request) -> web.Response:
        instance = await self.engine.disable(request.match_info["workflow_id"])
        return web.json_response(self.engine.serialize(instance, include_script=True))

    async def reload_workflow(self, request: web.Request) -> web.Response:
        instance = await self.engine.reload(request.match_info["workflow_id"])
        return web.json_response(self.engine.serialize(instance, include_script=True))

    async def run_workflow(self, request: web.Request) -> web.Response:
        instance = await self.engine.run(request.match_info["workflow_id"])
        return web.json_response(self.engine.serialize(instance, include_script=True))

    async def delete_workflow(self, request: web.Request) -> web.Response:
        await self.engine.delete(request.match_info["workflow_id"])
        return web.Response(status=204)

    @staticmethod
    async def _json(request: web.Request) -> dict[str, Any]:
        try:
            data = await request.json()
        except (ValueError, TypeError) as error:
            raise web.HTTPBadRequest(text="A JSON request body is required") from error
        if not isinstance(data, dict):
            raise web.HTTPBadRequest(text="The JSON body must be an object")
        return data


@web.middleware
async def errors(request: web.Request, handler: Any) -> web.StreamResponse:
    try:
        return await handler(request)
    except web.HTTPException:
        raise
    except KeyError as error:
        raise web.HTTPNotFound(text=str(error).strip("'")) from error
    except ValueError as error:
        raise web.HTTPBadRequest(text=str(error)) from error


def create_app(engine: WorkflowEngine, static_directory: Path) -> web.Application:
    frontend = WorkflowWeb(engine, static_directory)
    app = web.Application(client_max_size=256 * 1024, middlewares=[errors])
    app.router.add_get("/", frontend.index)
    app.router.add_get("/api/status", frontend.status)
    app.router.add_get("/api/workflows", frontend.list_workflows)
    app.router.add_post("/api/workflows", frontend.create_workflow)
    app.router.add_get("/api/workflows/{workflow_id}", frontend.get_workflow)
    app.router.add_put("/api/workflows/{workflow_id}", frontend.update_workflow)
    app.router.add_post("/api/workflows/{workflow_id}/enable", frontend.enable_workflow)
    app.router.add_post("/api/workflows/{workflow_id}/disable", frontend.disable_workflow)
    app.router.add_post("/api/workflows/{workflow_id}/reload", frontend.reload_workflow)
    app.router.add_post("/api/workflows/{workflow_id}/run", frontend.run_workflow)
    app.router.add_delete("/api/workflows/{workflow_id}", frontend.delete_workflow)
    return app
