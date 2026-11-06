# vic3-script-debugger

An IntelliJ plugin for debugging Victoria 3's Jomini script against the
running game.

Victoria 3 ships a **Debug Adapter Protocol server** for its script language.
Launch the game with `-script_debugger` and it listens on `127.0.0.1:6111`,
speaking standard DAP over `Content-Length`-framed JSON. That gives an
external client breakpoints, stepping, script call stacks, scope and variable
inspection, trigger evaluation with full reason trees, and non-halting
logpoints that stream evaluated expressions out of a running game.

```
victoria3.exe -script_debugger          # 127.0.0.1:6111
victoria3.exe -script_debugger=9229     # pick the port
```

It is in the public release build as of **1.15.0**. It is not a debug-build
feature and needs no special binary.

## Status

Early. The protocol is fully mapped and verified against a live game; the
plugin itself is not written yet.

**[docs/SCRIPT_DEBUGGER.md](docs/SCRIPT_DEBUGGER.md)** is the specification
this is being built from — the complete command surface, what `evaluate`
accepts, the catalogue of readable data, the protocol's traps, and two
reproducible sessions. Read it before touching the client code.

**[tools/dapprobe](tools/dapprobe)** is a reference client: a small Go DAP
probe that runs a session from a `.jsonl` script and prints the traffic. Every
transcript in the spec came from it. Use it to settle a protocol question
against a running game before committing the plugin to an assumption.

```
cd tools/dapprobe
go run . -script sessions/inspect.jsonl        # pause, walk the scope tree, resume
go run . -script sessions/logpoint-tap.jsonl   # non-halting value stream
```

It is a probe, not a library — the plugin will speak DAP through IntelliJ, not
through this. It is a separate Go module so it stays out of the JVM build.

## The short version

- **Every read needs a halt.** `stackTrace`, `scopes`, `variables` and
  `evaluate` fail with `"not stopped"` while the game runs, so state has to be
  read at a breakpoint or after `pause`. Do not build a polling refresh loop
  around pausing — it stutters the game.
- **Logpoints are the way to watch state continuously.** A breakpoint with a
  `logMessage` never halts; it interpolates `{...}` expressions and emits
  `output` events. One logpoint on a hot line produced 133 values in five
  seconds at full speed.
- **`evaluate` reads, it does not write.** Scope-prefixed script values
  (`this.gdp`, `this.capital.gdp`, `scope:state.average_sol`) and triggers,
  which come back with the whole localised yes/no breakdown. Effects cannot be
  run from outside.
- **Unrecognised commands return `success: true` with an empty body.** Thirteen
  DAP requests are silent no-ops. `initialize` is the only honest capability
  signal — never feature-detect by probing.
- **Treat it as single-client.** A second connection gets responses to its own
  requests but receives no events.
- **Detect by connecting.** On a game too old to have the feature the flag is
  simply an unknown option: no listener, no error, no log line.

## Licence

Not yet chosen.
