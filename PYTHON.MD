# Initiative Python Scripts

An Initiative Python script represents one local `External` device. It is
invoked by Initiative as:

```text
python3 <script-path> capabilities
python3 <script-path> status
python3 <script-path> <command> [arguments...]
```

`capabilities` and `status` are required. Commands listed by `capabilities`
are the actions Initiative may invoke.

## Capabilities

`capabilities` writes one command declaration per line to standard output.
Use a tab between the command name and each argument declaration.

```text
<command>\t<argument-name>:<type>\t...
```

Commands with no arguments contain only their name. Supported types are
`String`, `Integer`, `Long`, `Double`, and `Boolean`.

Integer arguments must always include inclusive minimum and maximum values:

```text
brightness\tvalue:Integer:0:100
```

Initiative uses that range to render an integer slider. Other argument types
use an input field in the Swing UI.

Example output:

```text
on
off
brightness\tvalue:Integer:0:100
```

Initiative turns this into these calls:

```text
python3 lights.py on
python3 lights.py off
python3 lights.py brightness 50
```

Do not print logs, banners, or warnings during `capabilities`; its standard
output is parsed as the command list. Comment lines beginning with `#` and
empty lines are ignored.

## Status

`status` must return a concise current-state description on standard output.
It is not an action and does not appear in the capability list. The Swing UI
polls it every 30 seconds, so it should be safe to call repeatedly and avoid
changing device state.

For example:

```text
power=on; brightness=50
```

## Commands and output

Action arguments arrive as ordinary command-line arguments. A successful
command exits with code `0`; its combined standard output and error output is
returned by `Initiative.INSTANCE.executeAction(...)` and shown in the UI.

On invalid input or a failed operation, write a useful error message and exit
with a non-zero code. Initiative treats that as an action failure.

## Minimal example

```python
import sys

if sys.argv[1] == "capabilities":
    print("on")
    print("brightness\tvalue:Integer:0:100")
elif sys.argv[1] == "status":
    print("power=on; brightness=50")
elif sys.argv[1] == "on":
    print("turned on")
elif sys.argv[1] == "brightness":
    value = int(sys.argv[2])
    print(f"brightness={value}")
else:
    raise SystemExit("unknown command")
```
