# Repository instructions

## Build and test

Use the Gradle wrapper and JDK 25:

```bash
./gradlew build
./gradlew test
./gradlew test --tests 'dev.ops.tools.launchpad.hooks.HookEventParserTest'
./gradlew test --tests 'dev.ops.tools.launchpad.hooks.HookEventParserTest.copilotCamelCase'
./gradlew quarkusDev
```

The `build` task runs the tests. Use Gradle's `--tests` filter with a fully qualified test class or test method to run a targeted test. There is no separately configured lint task.

## Architecture

This is a Quarkus daemon that translates agent lifecycle hooks into a shared view of agent sessions and controls a Novation Launchpad Mini MK2.

- `api/HookResource` exposes Claude Code, GitHub Copilot, and generic/ADK hook endpoints. `hooks/HookEventParser` normalizes their differing payload fields and event names into `HookEvent`; `HookResponses` converts the shared `HookOutcome` back into each runtime's response schema.
- `state/AgentStateService` owns session state and applies normalized events. It also suspends approval hooks until a Launchpad decision or configured timeout, and creates immutable `LaunchpadView` snapshots for rendering.
- `control/LaunchpadRenderer` maps a view and animation tick to a `LaunchpadFrame`. `RenderLoop` periodically renders frames; `MidiConnector`/`LaunchpadDevice` send changed LED values to hardware.
- `api/LaunchpadResource` exposes the same rendered frame and simulated button presses, so the daemon can be exercised without a connected device.
- `integrations/` contains the runtime-side Claude Code and Copilot hook configuration and the Google ADK plugin.

## Repository-specific conventions

- Keep runtime-specific payload handling at the hook boundary. Add or change aliases in `HookEventParser`, shared behavior in `AgentStateService`, and response formatting in `HookResponses`; preserve each runtime's response shape.
- Hook events are immutable records. Extend them through their `with...` methods; `SessionState` is mutable and guarded by the `AgentStateService` monitor.
- Approval resolution is asynchronous: do not block the event loop while waiting for a decision. Resolve queued approvals through `AgentStateService` and preserve its timeout/cancellation behavior.
- Keep `LaunchpadRenderer.render` a pure mapping from `LaunchpadView` and tick to frame. Keep hardware I/O in the MIDI layer so rendering and device behavior remain independently testable.
- Tests use JUnit 5. Parser, renderer, state, and MIDI behavior have focused unit tests; HTTP hook and virtual Launchpad behavior use `@QuarkusTest` and RestAssured.
