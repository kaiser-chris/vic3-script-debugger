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

## Building the plugin

The plugin targets IntelliJ IDEA 2026.3 (build 263), which needs Java 25. Gradle
finds or provisions a Java 25 toolchain itself, and runs on any JDK 17 or later.

```
gradlew test               # unit tests
gradlew test -Plive        # also attach to a running game started with -script_debugger
gradlew runIde             # try it in a sandbox IDE: Tools | Attach to Victoria 3
gradlew buildPlugin        # build/distributions/vic3-script-debugger-<version>.zip
```

So far it is a harness only. It finds running `victoria3.exe` processes, reads the
port from `-script_debugger=<port>` in their command line (6111 when the option
has no value), attaches, and detaches cleanly. The live test only attaches and
lists threads: it never halts the game or evaluates anything.

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
- **Malformed `evaluate` input can crash the game.** Bulk-probing guessed trigger
  forms caused an access violation. Wrong input can also return a normal-looking
  `0` or verdict instead of an error, so use documented forms and validate
  against the catalogue.
- **Unrecognised commands return `success: true` with an empty body.** Many DAP
  requests are silent no-ops. `initialize` is the only honest capability
  signal — never feature-detect by probing.
- **Treat it as single-client.** A second connection may receive no events, and
  in one test got no responses at all.
- **Detect by connecting.** On a game too old to have the feature the flag is
  simply an unknown option: no listener, no error, no log line.

## Licence

Not yet chosen.
