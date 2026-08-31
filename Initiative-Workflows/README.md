# Initiative Workflows

Initiative Workflows is an asyncio Python service that hosts restricted Lua automation scripts and serves an Initiative-styled frontend on port `1235`.

## Install and run

```bash
cd /home/gatoware/Initiative/Initiative-Workflows
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m initiative_workflows
```

Open `http://localhost:1235/` on the same machine, or `http://<pi-ip>:1235/` from another LAN device. The default Core URL is `http://10.0.0.17:1231`.

Configuration can be supplied with command-line options or environment variables:

```bash
.venv/bin/python -m initiative_workflows \
  --core-url http://10.0.0.17:1231 \
  --host 0.0.0.0 \
  --port 1235
```

The corresponding environment variables are `INITIATIVE_CORE_URL`, `WORKFLOWS_HOST`, `WORKFLOWS_PORT`, `WORKFLOWS_DATA_DIR`, and `WORKFLOWS_POLL_INTERVAL`.

Workflow scripts and their metadata are stored in `workflows/`. The two included examples are disabled initially so they cannot control a wrongly named device before they are reviewed.

## Lua API

```lua
local fan = initiative.device("Fan")

fan:run("Off")
local cached_status = fan:status()

initiative.every_day("06:00", function()
    fan:run("Off")
end)

initiative.when_inactive(
    initiative.device("Motion Sensor"),
    "SECONDS SINCE MOTION",
    1800,
    "12:00",
    "21:00",
    function()
        fan:run("Off")
    end
)

initiative.on_status(fan, function(status)
    initiative.log(status)
end)

initiative.on_run(function()
    fan:run("Off")
end)
```

`device:status()` returns the central cache and does not make a new blocking Core request. Status-based registrations cause that device to be polled by one shared poller. `initiative.on_run` is invoked by the frontend's **Run** button.

Lua cannot access Python builtins or attributes, `os`, `io`, `package`, `require`, dynamic loading, native libraries, or the debug API. Each runtime also has memory and instruction limits.

## Tests

```bash
cd /home/gatoware/Initiative/Initiative-Workflows
.venv/bin/python -m unittest discover -s tests -v
```
