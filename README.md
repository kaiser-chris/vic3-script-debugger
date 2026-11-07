# Victoria 3 Script Debugger

An IntelliJ plugin for debugging Victoria 3's Jomini script against the running
game: breakpoints, stepping, the game's scopes and script variables, and
evaluating script values and triggers at a halt.

It talks to the Debug Adapter Protocol server built into Victoria 3 since
1.15.0. Nothing is injected into the game, and nothing in it is changed: the
debugger can read and evaluate, but cannot run effects.

## Using it

1. Start the game with `-script_debugger` added to its launch options. It then
   listens on port 6111, or on another port with `-script_debugger=<port>`.
2. In the IDE, add a **Victoria 3 Script Debugger** run configuration and start it
   with **Debug**. It finds the running game and its port by itself.
3. Set breakpoints in `.txt` and `.gui` files of the base game or of a mod in your
   playset.

Once attached:

- **Breakpoints** halt the game. Conditions are evaluated by the game, and
  breakpoints that do not suspend become logpoints that print without halting.
  A breakpoint the game would never hit, for example in a file a later mod
  overrides, is marked invalid with the reason.
- **Halts** show the script stack and the scopes (`this`, `root`, `prev`, saved
  scopes) with their script variables. Step, Run to Cursor, Evaluate and watches
  work from there.
- **The console** shows logpoint output and the game's script errors as they
  happen.
- **Stopping** clears the breakpoints, resumes the game and detaches. The game
  keeps running.

Files are matched to the game by mod name and their path inside the mod, so a
working copy opened from anywhere works. Files the game loads show an indicator
in the editor's top-right toolbar.

### Things to know

- **Malformed expressions in Evaluate can crash the game.** The game parses them
  itself and does not always survive a mistake. Stick to forms from the game's
  script documentation, and do not experiment in a session you care about.
- **Lists cannot be expanded**, because the game only reports how many entries a
  list has.
- **Halting is disabled in multiplayer** by the game.

## Building

The plugin targets IntelliJ IDEA 2026.3, which needs Java 25. Gradle provisions
it, and runs on any JDK 17 or later.

```
gradlew buildPlugin        # build/distributions/vic3-script-debugger-<version>.zip
gradlew test               # unit tests
gradlew runIde             # a sandbox IDE with the plugin installed
gradlew verifyPlugin       # JetBrains Plugin Verifier against IDEA 2026.3
```

Install the zip with **Settings › Plugins › ⚙ › Install Plugin from Disk**.

Tests tagged `live` need a running game started with `-script_debugger` and are
skipped unless you ask for them with `gradlew test -Plive`. One of them halts the
game briefly. `LiveMappingTest` also needs `VIC3_WORKING_COPY_FILE` set to a
script file in a working copy of a mod in your playset.

## Releasing

Run the **Release** workflow from the Actions tab. Pick the part of the version to
increase (major, feature or patch) and whether to publish on the JetBrains
Marketplace. The workflow:

1. takes the highest release tag and increases it. The first release keeps the
   version in `gradle.properties`.
2. runs the tests, builds the plugin and runs the Plugin Verifier.
3. commits the new version to `gradle.properties` and pushes it with a tag.
4. creates a GitHub release with the plugin zip and the commits since the last
   release.
5. publishes to the Marketplace, but only for releases from `main`. Releases from
   other branches become GitHub pre-releases.

The Marketplace job runs in the `jetbrains-marketplace` environment. Its secrets:

| Secret | |
|---|---|
| `PUBLISH_TOKEN` | Required. A Marketplace token, from **My Tokens** in your JetBrains Marketplace profile. |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Optional. Sign the plugin yourself; without them the Marketplace signs it. |

Restrict the environment's deployment branches to `main`, so no other branch can
reach the token. The Marketplace only accepts updates to an existing plugin, so the
first version has to be uploaded by hand at
[plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add).

## Further reading

- [docs/SCRIPT_DEBUGGER.md](docs/SCRIPT_DEBUGGER.md) describes the game's debug
  server as observed against a live game: every request, what `evaluate`
  accepts, the protocol's quirks, and how editor files map to loaded files.
- [tools/dapprobe](tools/dapprobe) is a small Go client that replays DAP sessions
  from a script, for checking a protocol question against the game.

## Licence

[MIT](LICENSE)
