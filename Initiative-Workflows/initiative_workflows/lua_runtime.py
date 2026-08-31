from __future__ import annotations

from typing import Any, Protocol

from lupa import LuaError, LuaMemoryError, LuaRuntime, lua_type


class LuaSandboxError(RuntimeError):
    pass


class LuaHost(Protocol):
    def device_status(self, device_name: str) -> str | None: ...

    def queue_action(self, device_name: str, action_name: str, arguments: tuple[object, ...]) -> bool: ...

    def register_daily(self, time_text: str, callback: Any) -> None: ...

    def register_inactivity(
        self,
        device_name: str,
        status_key: str,
        seconds: float,
        start_time: str,
        end_time: str,
        callback: Any,
    ) -> None: ...

    def register_status(self, device_name: str, callback: Any) -> None: ...

    def register_manual(self, callback: Any) -> None: ...

    def log(self, message: str) -> None: ...


class LuaWorkflow:
    def __init__(
        self,
        host: LuaHost,
        *,
        memory_limit: int = 4 * 1024 * 1024,
        instruction_limit: int = 250_000,
    ) -> None:
        self.host = host
        self.instruction_limit = instruction_limit
        self.lua = LuaRuntime(
            encoding="utf-8",
            unpack_returned_tuples=True,
            register_eval=False,
            register_builtins=False,
            attribute_filter=self._deny_python_attributes,
            max_memory=memory_limit,
        )
        self._guards = self.lua.eval(
            """
            (function()
              local load_, sethook_, traceback_, error_, unpack_ =
                load, debug.sethook, debug.traceback, error, table.unpack

              local function guarded(limit, fn, ...)
                local arguments = {...}
                local function instruction_limit_reached()
                  error_("workflow instruction limit exceeded", 0)
                end
                sethook_(instruction_limit_reached, "", limit)
                local results = {xpcall(function()
                  return fn(unpack_(arguments))
                end, traceback_)}
                sethook_()
                return unpack_(results)
              end

              return {
                run = function(source, name, limit)
                  local chunk, compile_error = load_(source, "@" .. name, "t", _ENV)
                  if not chunk then return false, compile_error end
                  return guarded(limit, chunk)
                end,
                call = function(callback, limit, ...)
                  return guarded(limit, callback, ...)
                end
              }
            end)()
            """
        )
        self._device_factory = self.lua.eval(
            """
            function(name, run_action, read_status)
              local device = {name = name}
              device.run = function(self, action, ...)
                return run_action(action, ...)
              end
              device.status = function(self)
                return read_status()
              end
              return device
            end
            """
        )
        self._install_api()
        self._remove_unsafe_globals()

    @staticmethod
    def _deny_python_attributes(obj: object, attribute: object, is_setting: bool) -> str:
        raise AttributeError("Python attribute access is not available to workflows")

    @staticmethod
    def _require_callback(callback: Any) -> None:
        if lua_type(callback) != "function":
            raise ValueError("A Lua callback function is required")

    @staticmethod
    def _device_name(device: Any) -> str:
        if lua_type(device) != "table":
            raise ValueError("An Initiative device is required")
        name = device["name"]
        if not isinstance(name, str) or not name.strip():
            raise ValueError("An Initiative device name is required")
        return name

    def _install_api(self) -> None:
        api = self.lua.table()
        api["device"] = self._device
        api["every_day"] = self._every_day
        api["when_inactive"] = self._when_inactive
        api["on_status"] = self._on_status
        api["on_run"] = self._on_run
        api["log"] = lambda value="": self.host.log(str(value))
        globals_ = self.lua.globals()
        globals_["initiative"] = api
        globals_["print"] = lambda *values: self.host.log("\t".join(str(value) for value in values))

    def _remove_unsafe_globals(self) -> None:
        globals_ = self.lua.globals()
        for name in (
            "python",
            "os",
            "io",
            "package",
            "require",
            "dofile",
            "loadfile",
            "load",
            "debug",
            "coroutine",
            "jit",
            "ffi",
            "collectgarbage",
        ):
            globals_[name] = None

    def _device(self, name: object) -> Any:
        device_name = str(name).strip()
        if not device_name:
            raise ValueError("A device name is required")

        def run_action(action_name: object, *arguments: object) -> bool:
            action = str(action_name).strip()
            if not action:
                raise ValueError("An action name is required")
            return self.host.queue_action(device_name, action, tuple(arguments))

        def read_status() -> str | None:
            return self.host.device_status(device_name)

        return self._device_factory(device_name, run_action, read_status)

    def _every_day(self, time_text: object, callback: Any) -> None:
        self._require_callback(callback)
        self.host.register_daily(str(time_text), callback)

    def _when_inactive(
        self,
        device: Any,
        status_key: object,
        seconds: object,
        start_time: object,
        end_time: object,
        callback: Any,
    ) -> None:
        self._require_callback(callback)
        self.host.register_inactivity(
            self._device_name(device),
            str(status_key),
            float(seconds),
            str(start_time),
            str(end_time),
            callback,
        )

    def _on_status(self, device: Any, callback: Any) -> None:
        self._require_callback(callback)
        self.host.register_status(self._device_name(device), callback)

    def _on_run(self, callback: Any) -> None:
        self._require_callback(callback)
        self.host.register_manual(callback)

    @staticmethod
    def _result(result: Any) -> tuple[bool, object | None]:
        if isinstance(result, tuple):
            return bool(result[0]), result[1] if len(result) > 1 else None
        return bool(result), None

    def load(self, source: str, filename: str) -> None:
        try:
            ok, detail = self._result(self._guards["run"](source, filename, self.instruction_limit))
        except (LuaError, LuaMemoryError, MemoryError) as error:
            raise LuaSandboxError(str(error)) from error
        if not ok:
            raise LuaSandboxError(str(detail or "Lua workflow failed to load"))

    def call(self, callback: Any, *arguments: object) -> object | None:
        try:
            ok, detail = self._result(self._guards["call"](callback, self.instruction_limit, *arguments))
        except (LuaError, LuaMemoryError, MemoryError) as error:
            raise LuaSandboxError(str(error)) from error
        if not ok:
            raise LuaSandboxError(str(detail or "Lua callback failed"))
        return detail
