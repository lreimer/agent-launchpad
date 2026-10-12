# agent-launchpad

Visualise and control Claude Code, GitHub Copilot or Google ADK agent sessions using a Novation Launchpad Mini MK2.

A small Quarkus daemon receives the lifecycle hooks of the agent runtime via HTTP, renders the session state
onto the Launchpad and lets you approve or block tool executions, cancel subagents, trigger an emergency stop
or a manual compaction directly from the hardware.

## Build and Run

Requires JDK 25 (the Gradle wrapper is included).

```bash
# development mode with live reload
./gradlew quarkusDev

# build, run the tests and start the application
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

The daemon listens on `http://127.0.0.1:8080`. When no Launchpad is connected it keeps running with the
virtual Launchpad (see [REST API](#rest-api)), so everything can also be tried with `curl`.

## Launchpad Layout

```
┌─────────────────────────────────────────┐  ◄── Top Row (CC 104-111): Global Controls
│ [Stop] [ ] [ ] [ ] [Session] [ ] [ ] [Compact]
├─────────────────────────────────────────┼──┐
│  [Allow][Block][Session][ ] [ ] [ ] [ ] [ ] │[ ]│ ◄── Rows 1-2: Approval / Interactive Decision Zone
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[ ]│     (row 2: queue of further pending approvals)
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[x]│ ◄── Rows 3-6: one strip per active subagent,
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[x]│     side button cancels the subagent
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[x]│
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[x]│
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[ ]│ ◄── Row 7: Task / tool history (newest right)
│  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ] │[ ]│ ◄── Row 8: Stream telemetry ripple
└─────────────────────────────────────────┴──┘
                                             ▲
                                             └── Right Column: Context window gauge
```

The Launchpad Mini MK2 only has red and green LEDs, so the palette maps to the available colors:

| Hook(s) | Visualization |
|---|---|
| `PreToolUse` / `PermissionRequest` (pending approval) | Pad (1,1) flashing amber, (1,2) red, (1,3) yellow; row 2 shows the queue |
| `PostToolUse` | Bright green entry in the history row |
| `PostToolUseFailure` | Bright red entry in the history row |
| `UserPromptSubmit` | Pad (1,1) dim amber pulse (agent thinking) |
| `MessageDisplay` | Amber/yellow ripple across row 8, proportional to the streamed tokens |
| `Stop` | Pad (1,1) bright amber flash, then solid green |
| `StopFailure` | Pad (1,1) flashing red |
| `SubagentStart` | Green chasing animation in the subagent strip (rows 3-6) |
| Subagent waiting for an approval | Solid yellow strip |
| `SubagentStop` / `TaskCompleted` | Solid green strip |
| `TaskCreated` / running tool | Flashing yellow entry in the history row (amber when blocked) |
| Context usage | Right column fills bottom-up: 4× green, 2× amber, 2× red |
| `PreCompact` → `PostCompact` | Right column flashing orange |

Purple, cyan, blue and white from the original concept are substituted by flashing orange, green chase,
yellow and amber respectively.

### Controls

| Button | Action |
|---|---|
| Pad (1,1) | Allow the oldest pending tool call of all sessions (`permissionDecision: allow`) |
| Pad (1,2) | Block the oldest pending tool call (`permissionDecision: deny`) |
| Pad (1,3) | Allow and auto-approve this tool for the rest of the session |
| Side buttons rows 3-6 | Cancel the subagent of that strip: its further tool calls are denied (only the first 4 running subagents get a strip) |
| Top 1 (CC 104) | Emergency stop toggle: denies all pending and further tool calls and answers hooks with `continue: false`; optionally runs `launchpad.actions.emergency-stop-command` |
| Top 5 (CC 108) | Session indicator (lit while a session is active) |
| Top 8 (CC 111) | Manual compaction request; optionally runs `launchpad.actions.compact-command` |

## Interactive Approvals (Long-Polling Bridge)

Hook requests for tools matching `launchpad.approval.tool-pattern` are suspended asynchronously
(Mutiny `Uni` backed by a `CompletableFuture`) until a pad is pressed. The pending HTTP request is then
completed with the decision, e.g. for Claude Code:

```json
{ "hookSpecificOutput": { "hookEventName": "PreToolUse", "permissionDecision": "allow" } }
```

When nobody answers within `launchpad.approval.timeout`, the daemon returns `{}` and the agent continues with
its normal permission flow. Make sure the hook timeout of the agent runtime is larger than this timeout.

## Integrations

### Claude Code

Copy or merge [integrations/claude/settings.json](integrations/claude/settings.json) into
`.claude/settings.json` (project) or `~/.claude/settings.json` (user). It uses `http` hooks, `PreToolUse`
for `Bash|Edit|Write` goes to `/api/v1/hooks/pre-tool`, all other events to `/api/v1/hooks/claude`.
With a command hook the equivalent is:

```json
{ "type": "command", "command": "curl -s -X POST http://localhost:8080/api/v1/hooks/pre-tool -H 'Content-Type: application/json' -d @-" }
```

Instead of `PreToolUse` you can also register `PermissionRequest` to only be asked when Claude Code would
show a permission dialog. The context gauge uses `context_tokens` if present, otherwise the token usage of
the last assistant message in the session transcript.

### GitHub Copilot

Copy [integrations/copilot/hooks.json](integrations/copilot/hooks.json) to `.github/hooks/launchpad.json`
of your repository (Copilot CLI / coding agent). Because Copilot payloads do not contain the event name the
event is part of the URL (`/api/v1/hooks/copilot/{event}`). Command hooks with `curl` are used since HTTP
`preToolUse` hooks require HTTPS; `|| echo '{}'` keeps the agent working when the daemon is not running.

### Google ADK

Add the plugin from [integrations/adk/launchpad_plugin.py](integrations/adk/launchpad_plugin.py) to your runner:

```python
runner = InMemoryRunner(agent=root_agent, plugins=[LaunchpadPlugin()])
```

It posts to `/api/v1/hooks/adk` and returns an error result for tool calls that are blocked on the Launchpad.

## REST API

| Endpoint | Description |
|---|---|
| `POST /api/v1/hooks/{event}` | Claude Code hooks, `hook_event_name` from the payload takes precedence |
| `POST /api/v1/hooks/copilot/{event}` | GitHub Copilot hooks, e.g. `preToolUse` |
| `POST /api/v1/hooks/adk` | Generic hooks (Google ADK), answers `{"decision": "allow\|deny\|none", "remember", "stop", "reason"}` |
| `GET /api/v1/launchpad` | Virtual Launchpad: current LED colors (`*` = flashing) and pending approvals |
| `POST /api/v1/launchpad/pads/{row}/{col}` | Simulate a grid pad press (1-based) |
| `POST /api/v1/launchpad/side/{row}` | Simulate a side button press (1-based) |
| `POST /api/v1/launchpad/top/{button}` | Simulate a top row button press (1-based) |
| `GET /q/health` | Health check |

## Configuration

See [application.properties](src/main/resources/application.properties):

| Property | Default | Description |
|---|---|---|
| `launchpad.midi.enabled` | `true` | Connect to the MIDI device |
| `launchpad.midi.device-name` | `Launchpad Mini` | Substring of the MIDI device name |
| `launchpad.approval.timeout` | `PT2M` | Max. time to wait for a decision on the Launchpad |
| `launchpad.approval.tool-pattern` | `.*` | Regex of tool names requiring an approval |
| `launchpad.context.window-tokens` | `200000` | Context window size used by the gauge |
| `launchpad.stream.tokens-per-pad` | `100` | Streamed tokens per lit pad in the ripple row |
| `launchpad.render.interval` | `125ms` | LED render loop interval |
| `launchpad.actions.emergency-stop-command` | – | Optional command (comma separated argv, no shell) for the emergency stop |
| `launchpad.actions.compact-command` | – | Optional command (comma separated argv, no shell) for manual compaction |

The daemon binds to `127.0.0.1` only, since the hook endpoints can approve tool executions.
