# The script debugger: driving Jomini script from outside the game

Victoria 3 contains a **Debug Adapter Protocol server** for the script
language. It listens on a loopback TCP socket, speaks standard DAP over
`Content-Length`-framed JSON, and gives an external client breakpoints,
stepping, script call stacks, scope and variable inspection, trigger
evaluation with full reason trees, and — the part that matters most for
monitoring — **non-halting logpoints that stream evaluated expressions**.

```
victoria3.exe -script_debugger          # 127.0.0.1:6111
victoria3.exe -script_debugger=9229     # pick the port
```

Everything below was **verified against a live game**, not inferred, unless
explicitly marked otherwise — by driving the socket with a small DAP client
written for the purpose. The two sessions behind most of the claims are
reproduced in full in §14, so any of it can be re-run.

## 1. Which versions have it

**It is in the public release build as of 1.15.0**, so a plugin can rely on it
being available to ordinary players. It is not a debug-build feature.

Confirmed against `release/1.15.0` (`victoria3.exe` built 2026-11-05,
`caligula_rev 6338547985dbc271523e149f362a6fd93dc62975`):
`-script_debugger=8080` produced a listener on `127.0.0.1:8080` that completed
`initialize` → `launch` → `configurationDone` → `threads` with the
capabilities in §3.

Earlier builds do not have it. A 2026-09-03 build of `victoria3.exe` contains
zero `ScriptDebugger` strings, and the behaviour there is a **silent no-op, not
an error**: `HasOption("script_debugger")` gates the whole feature, so on a
binary without it the flag is just an unknown option — the game starts
normally, nothing listens, and there is no diagnostic anywhere.

A client should therefore detect by **connecting**, never by version-sniffing.
No listener means either the flag was not passed or the game is too old, and
those two are indistinguishable from outside.

## 2. Enabling it, and the port

The option is read in `CGameApplication::InitInstance`
(`source\logic\gameapplication.cpp:798`). Decompiled from the call site:

```c
if (!CommandLine.HasOption("script_debugger"))
    return;                                   // feature entirely off
uint16 port = 6111;                           // 0x17DF
CPdxStringView arg;
if (CommandLine.GetOptionArgument("script_debugger", &arg)) {
    int p = atoi(arg);
    if (p > 0 && p <= 65535) port = (uint16)p;
}
NJominiScriptDebugger::Init(port);
```

Single dash works (`-script_debugger=6111`, as used by the verified session).
An out-of-range value silently falls back to 6111 rather than failing. It binds
**127.0.0.1 only**, and there is no authentication or handshake secret —
anything local that can open the port can drive the debugger, including
halting the game.

It is not registered through `NCommandLine::SOptionDefinition`, so it does not
appear in the `--command_line_help` listing at all.

## 3. Capabilities and the command surface

`initialize` returns exactly:

```json
{ "supportsConfigurationDoneRequest": true,
  "supportsEvaluateForHovers": true,
  "exceptionBreakpointFilters": [
    { "filter": "null-scope", "label": "Break on null/missing scope", "default": false } ] }
```

followed by an `initialized` event. Handshake is the ordinary
`initialize` → `launch` → `setBreakpoints` / `setExceptionBreakpoints` →
`configurationDone`.

**Unrecognised commands are answered `success: true` with an empty body, not
rejected.** This is the single most important protocol quirk for a client
author: you cannot feature-detect by trying a request. The `initialize`
response is the only capability signal. Verified by sending `bogusCommand`,
which came back successful.

Handled (returns a real body or has an observable effect):

| Request | Notes |
|---|---|
| `initialize` | capabilities above |
| `launch` | empty body, required to progress |
| `configurationDone` | empty body |
| `setBreakpoints` | returns `breakpoints[].verified` |
| `setExceptionBreakpoints` | `filters: ["null-scope"]` |
| `threads` | always one: `{"id":1,"name":"Game"}` |
| `stackTrace` | frames with `source.name`, `line`, `name`, `id` |
| `scopes` | `Scope` and `Saved Scopes` |
| `variables` | scope objects and script variables |
| `evaluate` | see §5 |
| `continue`, `pause` | with `continued` / `stopped` events |
| `stepIn`, `next`, `stepOut` | `stopped` with `reason: "step"` |
| `disconnect` | leaves the game running |

Silently no-op (`success: true`, `body: {}`, nothing happens): `setVariable`,
`setExpression`, `completions`, `exceptionInfo`, `source`, `loadedSources`,
`modules`, `breakpointLocations`, `dataBreakpointInfo`, `stepBack`, `goto`,
`restart`, `terminate`.

Note `next` **is** supported — an earlier static reading of this file guessed
it was missing because no `next` string was found; stepping over works and
emits `reason: "step"`.

## 4. Everything that reads data requires a halt

`stackTrace`, `scopes`, `variables` and `evaluate` all fail while the game is
running:

```json
{ "command": "stackTrace", "success": false, "message": "not stopped" }
```

So any read has to be preceded by a halt: `pause`, a breakpoint hit, or the
`null-scope` exception filter. `pause` lands on the next script node evaluated,
which in practice is immediate — the game is evaluating GUI and map triggers
constantly.

**This is the central design constraint for a plugin.** Polling game state by
pause → read → continue stutters the game. The way to read continuously
without halting is logpoints (§7).

## 5. What `evaluate` can compute

Two expression forms are accepted, discriminated by `LooksLikeTrigger` /
`LooksLikeValueExpression`. Anything else returns
`not a valid trigger or script value`.

**Script values**, with a scope prefix — this is the data-read mechanism:

```
this.gdp                                  -> 9932952.87712
this.building_levels                      -> 3392
this.cached_ai_total_population           -> 50325897
this.num_incorporated_states              -> 38
this.capital.gdp                          -> 1641735.5006
scope:state.average_sol                   -> 10.7398
capital.num_provinces                     -> 6
```

Scope chains work: `this.`, `root.`, `prev.`, `scope:<name>.`, and bare
traversal from `this` (`capital.gdp`). A bare value name with no scope prefix
(`gdp`) fails. A scope-valued target used as a value (`this.capital`) returns
`0` rather than an error — silently useless, so validate against the catalogue
in §6 rather than trusting a numeric result.

**Triggers**, which return a verdict plus the full evaluation tree:

```
is_country_type = recognized
  -> yes — Yes: is_country_type; COUNTRY_TYPE: Recognized; Subject: Country Ottoman Empire (95);

country_rank >= rank_value:great_power
  -> yes — Yes: country_rank; COMPARATOR: greater or equal to, NUM: 7,
           CURRENT_VALUE: 7, RANK: [concept_great_power]; Subject: Country Ottoman Empire (95);

NOT = { is_at_war = yes }
  -> yes — Yes: not;
           No: is_at_war; Subject: Country Ottoman Empire (95);
```

Nested blocks are accepted, and the reason tree comes back with the localised
subject. That tooltip-style breakdown is a genuinely good thing to surface in
an IDE.

`context` may be `repl`, `hover` or `watch` — all three returned the same
result. `frameId` is **optional** while halted; omitting it uses the current
frame.

**What it cannot do: run effects.** There is no path from the DAP layer into
effect execution, and no `setVariable`/`setExpression` handler, so nothing here
mutates game state. Running effects on demand stays an in-game affair — the
Effect Runner (`cw\jomini\modules\script_system_gui\source\jomini_effect_runner.cpp`,
window title "Effect Runner"). **Read and evaluate from outside; execute from
inside.**

## 6. The catalogue of readable data

The game generates its own list. `game/docs/event_targets.log` documents every
scope traversal and value getter with its input and output scopes — **350
entries, 190 of which output `value`**, i.e. are directly evaluable. Sibling
files `triggers.log`, `effects.log`, `event_scopes.log`, `modifiers.log`,
`on_actions.log` and `custom_localization.log` cover the rest, and the console
command `script_docs` regenerates them.

Parsing it into a table:

```bash
awk '/^### /{n=substr($0,5);next} /^Input Scopes:/{i=substr($0,15)}
     /^Output Scopes:/{o=substr($0,16)} /^Requires Data:/{r=substr($0,16)}
     /^$/{if(n!=""){printf "%s\t%s\t%s\t%s\n",n,i,o,r; n=""}}' game/docs/event_targets.log
```

Output-scope distribution: 190 `value`, 25 `country`, 7 `state`, 7
`character`, 6 `hq`, 5 `law`, 5 `law_type`, 3 each `strategic_region` /
`state_region` / `market` / `interest_group`, and a long tail.

A country in scope has 75 value targets requiring no extra data. All 60 tested
returned live numbers on the first attempt — `gdp`, `army_size`, `credit`,
`income`, `infamy`, `legitimacy`, `literacy`-adjacent measures,
`naval_combat_power`, `num_incorporated_states`, the whole `cached_ai_*`
population family, and so on. Entries marked `Requires Data: yes` take an
argument and were not exercised.

This is the honest scope of "readable data": **the documented event-target
surface, evaluated against whatever scope the halt happens to be in**, plus
script variables (§8). It is not arbitrary access to C++ struct fields — but
for the things Jomini script itself can see, it is complete and it is the
game's own supported vocabulary rather than a reverse-engineered offset.

## 7. Logpoints: a non-halting data tap

The most useful discovery for anything monitoring-shaped. A breakpoint with a
`logMessage` never halts; it evaluates the message — interpolating `{...}`
expressions — and emits an `output` event.

```json
{"command":"setBreakpoints","arguments":{
  "source":{"path":"gfx/map/spline_network/route_graphics/01_road_vehicles.txt"},
  "breakpoints":[{"line":63,"logMessage":"tap gdp={this.gdp}"}]}}
```

produces a stream, with the game running at full speed:

```json
{"event":"output","body":{"category":"console","output":"tap gdp=21618605.2108\n"}}
{"event":"output","body":{"category":"console","output":"tap gdp=83787562.58652\n"}}
{"event":"output","body":{"category":"console","output":"tap gdp=388523.77876\n"}}
```

**133 events in 5 seconds** from one logpoint on a hot map-graphics trigger,
each a different country as the trigger ran per route. Pick a line that
executes in the scope you care about and you have a live feed of any expression
`evaluate` accepts, with no pausing and no memory reads.

`hitCondition` is accepted and verified on a breakpoint (unhandled hit-count
semantics were not measured).

## 8. What the scope tree looks like

`scopes` returns two, both `expensive: false`:

```
SCOPE "Scope"        ref=1
  this        = Country Ottoman Empire (95)   [expandable]
  root        = Country Ottoman Empire (95)   [expandable]
  prev        = (none)
  top scope   = "Root: Country Ottoman Empire (95)\n\nSaved event targets:\nstate: State Bosna (x51A0A0)"
SCOPE "Saved Scopes" ref=2
  scope:state = State Bosna (x51A0A0)         [expandable]
```

Expanding a scope object yields **that object's script variables** — the same
data that otherwise has to be reconstructed by reading the game's memory:

```
this = Country Ottoman Empire (95)
  slavery_recently_enacted            = yes
  sick_man_var                        = 0.00
  sick_man_separatist_var             = 0.00
  king_in_parliament_possible         = yes
  serfs_up_ach_possible               = yes
  healthy_man_of_europe_ach_possible  = yes
```

Named event targets appear under `Saved Scopes` as `scope:<name>` and expand
the same way. Countries render as `Country <Name> (<id>)`; other objects as
`State Bosna (x51A0A0)` or `TreatyArticle(4294967295)` — a mix of numeric ids
and hex handles, and `4294967295` is `0xFFFFFFFF`, i.e. a null handle you will
see in the wild.

Stack frames name the scripted trigger being evaluated, so scripted triggers
form a real call stack:

```
FRAME[0] name="gfx_car_condition_trigger" line=21 source=gfx/.../00_ep1_road_vehicles.txt
FRAME[1] name="untyped"                   line=20 source=gfx/.../00_ep1_road_vehicles.txt
```

## 9. Breakpoints and source paths

`setBreakpoints` verified against all three path forms — the response carries
`verified: true` and the breakpoint fires with `reason: "breakpoint"`:

- VFS-relative: `gfx/map/spline_network/route_graphics/01_road_vehicles.txt`
- game-relative: `game/gfx/map/.../01_road_vehicles.txt`
- absolute: `<steam>/steamapps/common/Victoria 3/game/gfx/.../01_road_vehicles.txt`

Forward slashes work throughout, which avoids JSON escaping. A path the game
has not loaded comes back `verified: false` with
`file not loaded by the game (yet)`.

`stackTrace` frames return only `source.name` (VFS-relative), never
`source.path`, so a plugin has to map back to workspace files itself — by VFS
path against the game directory and each active mod, in mod load order.

## 10. Session hygiene and concurrency

- **A client that exits while the game is halted leaves it frozen.** Always
  `continue` before `disconnect`. Both sample sessions in §14 do.
- Clear breakpoints before disconnecting, or they persist for the next client.
- `disconnect` leaves the game running and the listener open; reconnecting
  repeatedly works fine.
- **Treat it as single-client.** Two clients can be connected at once and each
  gets responses to its own requests, but events are delivered to only one: with
  client A owning a logpoint, A received 133 `output` events and B received 0
  over the same window. A second connection therefore cannot act as an observer.
  Multiplex over one connection. (The precise ownership rule was not
  characterised — only that B saw nothing.)
- Multiplayer disables halting entirely:
  `[script debugger] halting is disabled in multiplayer to avoid desync;
  breakpoints are ignored.` Logged once, guarded by `_WarnedMultiplayer`. The
  server still accepts connections; breakpoints, stepping and any halt-dependent
  `evaluate` are inert. Not verified live — single-player only in practice.

## 11. Notes for an IntelliJ plugin

Shape that follows from the above:

- **Debugger integration is a good fit.** IntelliJ's XDebugger maps onto this
  almost directly: breakpoints → `setBreakpoints`, the variables view →
  `scopes`/`variables`, watches and the evaluate-expression dialog → `evaluate`,
  and `supportsEvaluateForHovers` means editor hover can show a value. Since
  IntelliJ already has DAP client support, most of the transport is off the
  shelf.
- **Do not model state monitoring as polling.** Every read needs a halt, so a
  refresh loop that pauses would stutter the game. Use logpoints as taps
  instead: install them on lines that run in the scopes of interest and consume
  the `output` stream. That is the direct replacement for the memory-scanning
  approach, and it is supported rather than reverse-engineered.
- **Feature-detect only from `initialize`.** Everything else answers success.
- **Surface trigger reason trees.** `evaluate` on a trigger returns the full
  localised yes/no breakdown; that is hard to get any other way and is exactly
  what a modder debugging a condition wants.
- **Map `source.name` to workspace files yourself**, VFS-relative, honouring mod
  load order.
- **Detect by connecting, and say so clearly when it fails.** The feature is in
  the public branch from 1.15.0, but on an older build — or with the flag
  missing — the game starts normally and nothing listens, with no diagnostic.
  A failed connect should tell the user to check both the game version and the
  `-script_debugger` launch option, since the two failure modes look identical.
- Effects still cannot be run from outside (§5). A plugin that wants
  "run this effect now" has no channel for it today.

## 12. Things that are not the answer

Ruled out while looking, recorded so nobody re-treads it:

- **`CPdxIpcServer`** — Clausewitz carries an AF_UNIX request/response server
  that would suit this well, with a socket under
  `%LOCALAPPDATA%\Paradox Interactive`, length-prefixed framing and peer
  verification by PID, user SID and integrity level. It is dead code: nothing
  calls `RegisterDispatcher`, and without a dispatcher `Initialize` refuses to
  start, so no socket is ever created. Worth re-checking after a patch, but it
  is not a channel today.
- **No HTTP, WebSocket or JSON-RPC server.** No `HTTP/1.1`, `GET /`,
  `Sec-WebSocket`, `jsonrpc` or `0.0.0.0` anywhere in the binary. The DAP
  socket is the only listener outside multiplayer netcode.
- **`stdio_console`** is output only — `AllocConsole`, redirect stdout/stderr,
  never reads stdin. The console-subsystem launcher next to the game gives you
  a console to read, not a pipe to write commands into.
- **`pdx_red_king.dll`** is PlayFab A/B testing; its `ExecuteCloudScript` is
  PlayFab's server-side scripting, unrelated to Jomini.
- **Multiplayer netcode** carries typed game commands, not script, and the
  console is disabled there: `Console not available in multiplayer or ironman
  mode.`

## 13. Adjacent, still useful

- **`-run_console_action[_main|_clausewitz]=<command>`** — "Runs the specified
  in-game console command, then exits." Three variants at three init stages.
  Genuinely external and can run anything the console can, but one-shot at
  startup, and the process exits afterwards.
- **`-filewatcher`** plus the `reload` console command, for picking up edited
  script in a running game. Reloads definitions; executes nothing.
- **`common/console_command_macros`** — mod-definable named console macros with
  arguments (see `game/common/console_command_macros/00_macros.txt`).
- **`scripted_tests`** console command, with an optional `before`/`none`/`after`
  save-on-failure argument.

## 14. Reproducing this

Both sessions below are line-oriented: each line is one DAP request, sent in
order over a single connection, with the client filling in `seq` and `type`
and waiting for the matching response. Framing is
`Content-Length: <n>\r\n\r\n<json>`.

Any DAP client will do. The transcripts in this document came from
[`tools/dapprobe`](../tools/dapprobe), the reference client in this
repository, which also walks `stackTrace` → `scopes` → `variables`
recursively and batches `evaluate` calls — those steps need the previous reply
before the next request can be built, so they are directives rather than
script lines:

```
cd tools/dapprobe
go run . -script sessions/inspect.jsonl
go run . -script sessions/logpoint-tap.jsonl -idle 6
```

**Inspect the scope tree at a halt.** Pauses the game, walks everything in
scope, evaluates a few expressions, resumes. Note the `continue` before
`disconnect` — without it the game stays frozen.

```jsonl
{"command":"initialize","arguments":{"clientID":"probe","adapterID":"jomini","linesStartAt1":true,"columnsStartAt1":true,"pathFormat":"path"}}
{"command":"launch","arguments":{}}
{"command":"configurationDone","arguments":{}}
{"command":"pause","arguments":{"threadId":1}}
{"command":"stackTrace","arguments":{"threadId":1,"startFrame":0,"levels":100}}
{"command":"scopes","arguments":{"frameId":0}}
{"command":"variables","arguments":{"variablesReference":1}}
{"command":"evaluate","arguments":{"expression":"this.gdp","context":"repl","frameId":0}}
{"command":"evaluate","arguments":{"expression":"country_rank >= rank_value:great_power","context":"repl","frameId":0}}
{"command":"continue","arguments":{"threadId":1}}
{"command":"disconnect","arguments":{}}
```

Allow a moment after `pause` for the `stopped` event before issuing
`stackTrace`; the requests that need a halt fail with `"not stopped"` until it
arrives.

**A non-halting tap.** Installs a logpoint and listens. The game never pauses;
every evaluation of that line emits an `output` event with the expression
substituted. Pick a line that runs in the scope you care about.

```jsonl
{"command":"initialize","arguments":{"clientID":"probe","adapterID":"jomini","linesStartAt1":true,"columnsStartAt1":true,"pathFormat":"path"}}
{"command":"launch","arguments":{}}
{"command":"setBreakpoints","arguments":{"source":{"path":"gfx/map/spline_network/route_graphics/01_road_vehicles.txt"},"breakpoints":[{"line":63,"logMessage":"tap gdp={this.gdp}"}]}}
{"command":"configurationDone","arguments":{}}
{"command":"setBreakpoints","arguments":{"source":{"path":"gfx/map/spline_network/route_graphics/01_road_vehicles.txt"},"breakpoints":[]}}
{"command":"disconnect","arguments":{}}
```

Leave several seconds between `configurationDone` and the clearing
`setBreakpoints` — that gap is the listening window. Clear breakpoints before
disconnecting or they persist for the next client.
