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

Corrections and additions dated 2026-11-06 come from a wider scrape of a
modded 1.15.0 game that was kept **paused** throughout. Three mods were active:
Expanded Topbar Framework, Manaflow and Community Mod Framework.

> **Hazard: malformed `evaluate` input can crash the game.** See §5 before
> sending anything to a game that matters.

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
| `threads` | always one thread with id 1, named `Game` while running and `Script (halted)` during a halt |
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

The same empty success came back, both running and halted, from `attach`,
`setFunctionBreakpoints`, `setDataBreakpoints`, `setInstructionBreakpoints`,
`gotoTargets`, `stepInTargets`, `reverseContinue`, `restartFrame`, `readMemory`,
`writeMemory`, `disassemble`, `cancel` and `locations`. `setVariable` and
`setExpression` were only tried by writing a variable's current value, so that
test shows the reply but not whether a different value would be ignored.
`terminateThreads` and `disconnect` with `terminateDebuggee` have not been sent,
because they could end the game.

Note `next` **is** supported — an earlier static reading of this file guessed
it was missing because no `next` string was found; stepping over works and
emits `reason: "step"`.

**`threadId` in `stopped` events is not stable.** Both 1 and 3 were reported,
although `threads` lists only id 1. Requests sent with `threadId: 1` worked
either way, so use 1 rather than echoing the event's value.

Handles are checked inconsistently. `evaluate` with an unknown `frameId` fails
with `no frame`, while `scopes` with an unknown or missing `frameId` succeeds,
and `variables` with an unknown reference returns an empty list.

## 4. Everything that reads data requires a halt

`stackTrace`, `scopes`, `variables` and `evaluate` all fail while the game is
running:

```json
{ "command": "stackTrace", "success": false, "message": "not stopped" }
```

So any read has to be preceded by a halt: `pause`, a breakpoint hit, or the
`null-scope` exception filter. `pause` lands on the next script node evaluated,
which in practice is immediate — the game is evaluating GUI and map triggers
constantly. This holds with the game itself paused: a paused game still does not
count as stopped until a debugger halt, and `pause` still lands.

**This is the central design constraint for a plugin.** Polling game state by
pause → read → continue stutters the game. The way to read continuously
without halting is logpoints (§7).

## 5. What `evaluate` can compute

Two expression forms are accepted, discriminated by `LooksLikeTrigger` /
`LooksLikeValueExpression`. Anything else fails with either
`not a valid trigger or script value` or, when it was parsed as a trigger,
`Unknown trigger type: <name>, near line: 1`.

**Malformed input can crash the game.** On 2026-11-06, bulk-probing guessed
forms (`= yes`, `> 0`, `= { always = yes }`) against every documented trigger
ended in an unhandled access violation in `victoria3.exe`. The last thing logged
was:

```
Expected "has_employee_slots_filled = {", but got "has_employee_slots_filled = yes". Trigger Runner:1
```

That was evaluated on a building, for a trigger the docs define as taking a
block. So the parser reported the error, and the game died anyway. The cause is
not fully pinned down: the next form queued was `has_employee_slots_filled > 0`,
which may have been what crashed it, and the crash stack has no symbols. After a
restart, about 50,000 expressions in documented forms ran without a crash.

So do not treat `evaluate` as a safe read. Do not send either expression above
to a game you care about. A client that forwards typed text should warn about
this, and anything sending expressions in bulk should use only documented forms
on documented scopes, journal each expression before sending it, and save
results as it goes.

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

Global links work from any halt, whatever `this` happens to be, so reads need
not depend on where the game stopped: `c:GBR.gdp`, `c:GBR.capital.gdp`,
`c:GBR.ig:ig_armed_forces.ig_clout`, `c:GBR.var:<name>`,
`c:GBR.modifier:<key>`. Targets that take a country argument accept `:root`
(`c:GBR.relations:root`), but `c:GBR.relations:c:FRA` is rejected.

Expressions are checked against scope types, so `s:STATE_TEXAS.gdp` is rejected
because `gdp` does not take a state region. Other wrong input is not caught, and
returns an answer that looks normal:

| Expression | Result |
|---|---|
| `c:NOPE.gdp`, `scope:bogus.gdp`, `global_var:bogus.gdp` | `0` |
| `c:GBR.var:bogus`, `c:GBR.modifier:bogus` | `0` |
| `1+1` | `1` |
| `c:GBR.gdp + 1` | the GDP, ignoring `+ 1` |
| `always = maybe` | `no` |

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

Only the leading `yes` or `no` is the verdict. Inner `Yes:` and `No:` lines are
tooltip wording: `always = no` returns `no — No: \nYes: always; ...`. A verdict
also does not show that the expression was valid, because errors go to the
`stderr` output stream (§7) and `error.log`. `is_game_paused` returned `no` for
both `= yes` and `= no` on a paused game, so a verdict alone cannot be trusted
there either.

Reason trees carry more than the verdict, but not always a number:

- **Objects with no dotted path**, such as pops and formations, are reached
  through `any_*` blocks, and the tree names only the first match. This gives a
  verdict and a subject, **not a value**: in 62 of 62 accepted comparisons like
  `c:GBR = { any_military_formation = { power_projection > -1000000000 } }`, the
  tree had no `CURRENT_VALUE`, although the same style of comparison on a directly
  reached object does include it.
- **Defines** come back in `Object:`, for example
  `c:GBR = { gdp > define:NAI|STRATEGY_RANDOM_FACTOR }`. `c:GBR.define:...` is
  rejected. 2,716 of 3,036 numeric defines were read this way.

Nothing returns a list. `any_country = { count = all always = yes }` shows one
subject, so collections have to come from the game's files and be checked key
by key, for example with `c:<TAG> = { always = yes }`.

`context` may be `repl`, `hover`, `watch` or `clipboard` — all returned the same
result. `frameId` is **optional** while halted; omitting it uses the current
frame.

Every `evaluate` observed returned `variablesReference: 0`, so a result cannot
be expanded. Only objects in scope at the halt can (§8). `c:GBR` alone is
rejected and `c:GBR.capital` returns `0`. GUI data functions and localisation
are also out of reach: `c:GBR.GetName` and `[GetPlayer.GetName]` are rejected.

Evaluating adds log noise. Each call appears to re-run validation, re-emitting
`used but is never set` warnings for names in game and mod script, along with any
invalid names sent earlier in the session. They reach `error.log` and the
`stderr` output stream.

**What it cannot do: run effects.** There is no path from the DAP layer into
effect execution, and no `setVariable`/`setExpression` handler, so nothing here
mutates game state. Running effects on demand stays an in-game affair — the
Effect Runner (`cw\jomini\modules\script_system_gui\source\jomini_effect_runner.cpp`,
window title "Effect Runner"). **Read and evaluate from outside; execute from
inside.**

## 6. The catalogue of readable data

The game generates its own list, under
`Documents/Paradox Interactive/Victoria 3/`, not the install folder.
`docs/event_targets.log` documents every scope traversal and value getter with
its input and output scopes — **384 entries in the copy measured, 214 of which
output `value`**, i.e. are directly evaluable. The counts depend on the build
and on the mods active when the file was generated. Sibling files
`docs/triggers.log`, `docs/effects.log`, `docs/modifiers.log`,
`docs/on_actions.log`, `docs/custom_localization.log` and `logs/event_scopes.log`
cover the rest, and the console command `script_docs` regenerates them. Check the
files' dates, because they can be older than the game build.

Parsing it into a table:

```bash
awk '/^### /{n=substr($0,5);next} /^Input Scopes:/{i=substr($0,15)}
     /^Output Scopes:/{o=substr($0,16)} /^Requires Data:/{r=substr($0,16)}
     /^$/{if(n!=""){printf "%s\t%s\t%s\t%s\n",n,i,o,r; n=""}}' docs/event_targets.log
```

Output-scope distribution, from an earlier copy of the file with 350 entries:
190 `value`, 25 `country`, 7 `state`, 7 `character`, 6 `hq`, 5 `law`, 5
`law_type`, 3 each `strategic_region` / `state_region` / `market` /
`interest_group`, and a long tail.

A country in scope has 80 value targets requiring no extra data and 33 taking an
argument. All 113 were accepted on one country, the 33 with `:root`, and the 80
were accepted on all 439 live countries — `gdp`, `army_size`, `credit`, `income`,
`infamy`, `legitimacy`, `naval_combat_power`, `num_incorporated_states`, the
whole `cached_ai_*` population family, and so on. Accepted does not mean
meaningful, since a wrong expression can return `0` (§5).

Live objects were reached for 67 of 83 scope types. The rest either needed world
state that was absent at the time, such as a battle, or are value types with no
object behind them.

`modifier:<key>` reads current modifier values on the documented scopes: 2,778
were read on one country's objects, and 167 were nonzero. Unknown keys return
`0`, so check them against `docs/modifiers.log`.

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

Message handling, measured on a hot line:

| In `logMessage` | Output |
|---|---|
| `{c:GBR.gdp}`, `{this.gdp}` | the value |
| `{is_at_war = yes}` | `no (No: ...)`, the verdict and tree |
| `{this}`, `{bogus}`, `{this.GetName}` | the text inside the braces, unchanged |
| `{c:GBR.capital}` | `0` |
| `{}` | `(no scope)` |
| `{{c:GBR.gdp}}` | `Unknown trigger type: {, near line: 1` |
| `[GetPlayer.GetName]` | unchanged, since square brackets are not expanded |

**Script errors arrive on the same stream.** `output` events with
`category: "stderr"` carry the game's script errors and warnings live, such as
`Error: "Unknown trigger type: ..." in file: "Trigger Runner" near line: 1`. So
a client can show them without tailing `error.log`, but needs to filter them
because of the noise noted in §5. Logpoint output uses `category: "console"`.

On breakpoints, a false `condition` never stopped in the time measured and a true
one stopped at once. An invalid `condition` (`bogus`) still stopped, so it does
not protect against a typo. `hitCondition` looks like a hit count: `50` took about
20 times longer to stop than `3`. Exact counts were not measured.

Which script runs at all depends on game state. With the game paused and
logpoints armed for about 6 seconds per folder, `common/script_values`,
`common/scripted_guis`, `common/scripted_triggers`, `common/decisions`,
`common/diplomatic_actions` and a few route lines in `gfx` fired.
`common/on_actions`, `common/laws`, `common/buildings` and `common/ai_strategies`
did not, and neither did `events`, `common/journal_entries`,
`common/scripted_effects` or `common/history`, which were only partly covered.
These may simply need a running simulation, which this test did not exercise.
**Logpoints on about 164,000
`.gui` lines never fired**, at about 4 seconds per batch with no particular window
open. That suggests `.gui` files may not be debuggable, but it is not settled and
needs a test with the relevant window open.

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

A variable holding an object expands in turn, for example
`gate_magic_researcher = Character Acton Allen (7580)`. Variable lists show only
a count, such as `list:com_topbar_first_line = "7 entries"`, and cannot be
expanded.

**Do not cache `variablesReference`.** References are allocated as things are
expanded and every `scopes` call returns new ones, so `1` and `2` are not fixed
ids for the two scopes. Walk `scopes` then `variables` in order at each halt.

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

Stepping goes further than scripted triggers: `stepIn` also enters individual
triggers and links, so frame names include `is_at_war`, `script value`,
`scope:state`, `market` and `mg:automobiles`. Stacks reached a depth of 8 in a
700-step trace.

## 9. Breakpoints and source paths

`setBreakpoints` verified against all three path forms — the response carries
`verified: true` and the breakpoint fires with `reason: "breakpoint"`:

- VFS-relative: `gfx/map/spline_network/route_graphics/01_road_vehicles.txt`
- game-relative: `game/gfx/map/.../01_road_vehicles.txt`
- absolute: `<steam>/steamapps/common/Victoria 3/game/gfx/.../01_road_vehicles.txt`

Forward slashes work throughout, which avoids JSON escaping. A path the game
has not loaded comes back `verified: false` with
`file not loaded by the game (yet)`.

**`verified` is about the file, not the line.** Lines `0`, `-1` and `99999` all
come back `verified: true`, on a file where few lines ever execute. A verified
breakpoint may therefore never fire. It also does not mean the file holds
executable script: 4,727 of 4,820 `.txt` and `.gui` files verified, including
`common/history` and `common/dna_data`. Most of the rest were `licenses`, `fonts`
and `content_source`.

That makes `setBreakpoints` a usable stand-in for the missing `loadedSources`,
by setting and clearing a breakpoint per file. It handled about 700 requests a
second, whereas `evaluate` manages about 90.

Paths are not case-sensitive, and backslashes were accepted as well. The path has
to be in `source.path`: the same value in `source.name` comes back unverified.

`stackTrace` frames return only `source.name` (VFS-relative), never
`source.path`, so a plugin has to map back to workspace files itself — by VFS
path against the game directory and each active mod, in mod load order.

### Mapping editor files to loaded files

**Map by mod name and root-relative path, never by absolute path.** The file
open in the IDE and the file the game loaded are often the same file under a
different path, and sometimes a different copy of it. One real setup, checked
on 2026-11-07, shows three spellings of one file:

| Who | Path |
|---|---|
| IDE project | `D:\Development\projects\victoria3\gate\mod\common\on_actions\403_on_action_misc.txt` |
| Game (`content_load.json`) | `D:\Windows\Profile\Paradox Interactive\Victoria 3\mod\manaflow\common\on_actions\…` |
| Through Documents | `C:\Users\<user>\Documents\Paradox Interactive\Victoria 3\mod\manaflow\…` |

There, `mod\manaflow` is a junction to the repository's `mod` folder, and
`Documents` is itself a junction. The game recorded the junction's own path,
not its target. The repository root (`gate`) is not the mod root, and the
repository name (`gate`) differs from the folder the game loads (`manaflow`).

**Step 1: find the root of the open file.** Walk up from the file to the first
folder containing `.metadata/metadata.json`. That folder is the mod root, and
the path below it, with forward slashes, is the VFS path:
`common/on_actions/403_on_action_misc.txt`. Mods are never nested inside each
other, so the first match is the root. A project can still hold several mods
side by side: the same repository had `gate/mod`, plus `gate/cmf` and `gate/etf`,
junctions to two dependency mods, each with its own `.metadata`. So resolve the
root per file, not per project. A file under no mod root and not under the game's `game/` folder,
such as a repository's `documentation/` or `script/`, cannot be mapped, so show
no breakpoint gutter for it. For game files the root is the install's `game/`
folder, so a configured install path is easier than detecting one.

**Step 2: match that root to a loaded mod, by the `name` in `metadata.json`.**
- **Why `name`:** `id` is optional and only used for dependencies, so many mods
  omit it. 28 of 56 Workshop items had it empty. Every one of the 74 metadata
  files checked had a `name`, and names are unique within a playset.
- **The loaded set:** read `enabledMods[].path` from `content_load.json` in the
  user data folder, then each path's `metadata.json`. Do not scan the `mod`
  folder or the Steam Workshop folder: names are unique within a playset, not on
  disk. The same setup had the Manaflow name in `mod/gate` (a real copy),
  `mod/manaflow` (the junction) and a Workshop item, and the Community Mod
  Framework name in four places. Only `content_load.json` says which copy runs.
- **The order:** the order of `enabledMods` is the best available guess at load
  order. It has not been checked against the game.
- **Parsing:** JSON is always UTF-8 by spec, and names use it, for example
  `[1.15] Manaflow: Ankaris Arrival [English | 汉化 | 한국어]`. Two things break
  strict parsers:
  - **A byte order mark:** `metadata.json` can start with a UTF-8 BOM. RFC 8259
    forbids emitting one, and many JSON libraries reject it or treat it as part
    of the first token. Strip a leading `EF BB BF` before parsing.
  - **Invalid JSON:** one Workshop file was missing a comma, but its name was
    still readable.
  
  So parse leniently, and fall back to extracting `"name"` by pattern when full
  parsing fails.
- **Name drift:** names often carry the game version (`[1.15] …`), so a working
  copy on a branch for the next version may no longer match the loaded copy
  exactly. When nothing matches, let the user bind a project folder to a loaded
  mod by hand, and remember that.

**Step 3: decide whether the open copy is the loaded copy.** Matching the name
does not mean the files are the same: the game may run the Workshop copy while
the IDE has the repository, or another branch or checkout of it. Compare the
open file with the same VFS path under the loaded mod's folder:
- **Identical** (as in the example above, where both hashes matched): breakpoints
  map line for line.
- **Different:** a breakpoint lands on that line number in the loaded file, so
  warn, or offer to open the loaded copy.
- **Missing from the loaded copy:** the game cannot have this file from this mod,
  so the breakpoint cannot verify.

Matching files on disk do not guarantee the game has this version in memory.
Edits made after the game started are not loaded until a reload, and the
debugger offers no way to see what was loaded (§3).

**Step 4: check that this mod's file is the one in effect.**
- **Overrides:** a later mod with the same VFS path overrides it. A breakpoint on
  an overridden copy lands on the same line of the winning file, so flag
  overridden files instead of setting breakpoints silently.
- **`replace_paths`:** listed in a mod's `game_custom_data`, for example
  `"common/history/countries"`. Each entry stops the game loading the **base
  game's** files directly in that folder. Mods' files there are still loaded,
  and so are the base game's files in its subfolders, since the entry is not
  recursive. So a base game file is out of play when any loaded mod lists its
  exact folder, while a mod file never is. This comes from how mods behave, not
  from the debugger: none of the three mods in the tested playset use
  `replace_paths`, so how `verified` reports such a file is unknown. Given the
  suffix matching below, assume it says nothing about it.

**Step 5: send the VFS-relative path, and treat `verified` as a weak signal.**
Tested on 2026-11-07 against a mod file only Manaflow provides, and a base game
file:
- **Suffix matching:** every spelling verified, and logpoints set through each
  one fired: the IDE path, the loaded path, game-relative, and absolute game
  paths. So did `X:/nowhere/at/all/common/scripted_triggers/…`,
  `mod/common/on_actions/…`, an absolute game path under a wrong install root,
  and the path to `mod/gate`, a copy the game did not load. The server appears
  to match the end of the path against what it loaded, which is why the root
  does not matter. The exact rule was not measured.
- **What that means:** `verified` only says some loaded file has that ending. It
  cannot tell which copy or which root the user meant, so the mapping in steps
  1–4 has to be right before a breakpoint is sent. The server will not catch a
  wrong one. Send the VFS-relative path, so that what the plugin sent and what
  it meant are the same thing.
- **One breakpoint list per file:** different spellings of one file share a
  single breakpoint list. Setting line 81 through the VFS path removed a
  breakpoint on line 82 that had been set through the IDE path. Clearing through
  either spelling clears the file. So collect breakpoints per VFS path, across
  every open copy and project that maps to that path, and always send that file's
  complete list.

**Going the other way:** for a frame's `source.name`, find the winning loaded
copy for that VFS path in load order. Then, if an open project is bound to that
mod's name and its copy matches (step 3), open the project file. Otherwise open the
loaded file, read-only. Compare paths without regard to case on Windows.

## 10. Session hygiene and concurrency

- **A client that exits while the game is halted leaves it frozen.** Always
  `continue` before `disconnect`. Both sample sessions in §14 do.
- Clear breakpoints before disconnecting, or they persist for the next client.
- `disconnect` leaves the game running and the listener open; reconnecting
  repeatedly works fine.
- **Treat it as single-client.** In one test, two clients connected at once each
  got responses to their own requests, but events went to only one: with client A
  owning a logpoint, A received 133 `output` events and B received 0 over the same
  window. In a later test on 2026-11-06, a second client got no reply at all to
  `initialize`, or to its next requests, within 15-second timeouts while the first
  was connected and the game was running. The two results conflict and the later
  one was tried only once. Either way, a second connection cannot be relied on, so
  multiplex over one connection.
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
- **Guard anything that reaches `evaluate`.** Malformed input can crash the game
  (§5). Check typed expressions against the catalogue before sending, offer to
  send anyway rather than blocking outright, and never auto-probe with guessed
  forms. Hover and watch evaluate without an explicit send, so they need this
  most.
- **Do not trust a plain result.** Wrong input often returns `0`, or a verdict
  (§5). Validate names against the catalogue and show `stderr` output next to the
  result.
- **Show script errors from the `stderr` stream**, filtered for the repeated
  `never set` warnings (§7).
- **Re-read handles at each halt.** Do not cache `variablesReference`, and send
  `threadId: 1` regardless of what `stopped` reports (§3, §8).
- **Do not present `verified` as "this line will hit".** It only means the game
  read the file (§9). Whether `.gui` breakpoints can work is still open (§7).
- **Surface trigger reason trees.** `evaluate` on a trigger returns the full
  localised yes/no breakdown; that is hard to get any other way and is exactly
  what a modder debugging a condition wants.
- **Map `source.name` to workspace files yourself**, VFS-relative, honouring mod
  load order. Identify an open file's mod by its `.metadata` root and that
  metadata's `name`, not by absolute path, since junctions, copies and Workshop
  duplicates all break path matching (§9). `verified` will not catch a wrong
  mapping, and each file has one breakpoint list whatever path is used.
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
