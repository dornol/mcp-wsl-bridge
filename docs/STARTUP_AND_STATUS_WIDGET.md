# Automatic Startup and Status Widget

## Background

The bridge is currently instantiated from
`AppLifecycleListener.appFrameCreated()`. IntelliJ application services are
created lazily, so the bridge does not start until something requests
`McpBridgeService`. In practice, the lifecycle callback is not a reliable
enough trigger after an IDE restart.

There is a second startup race: the bridge can run before IntelliJ's built-in
MCP server begins listening. The first refresh then reports that the target was
not found, and the next attempt is delayed by the fixed 10-second refresh
interval.

The result is that the WSL endpoint can remain unavailable after IntelliJ is
restarted, with no visible indication inside the IDE.

## Goals

- Start the bridge automatically whenever an IntelliJ project is opened.
- Keep application lifecycle startup as an idempotent fallback.
- Retry quickly while IntelliJ's built-in MCP server is starting.
- Return to a low-frequency refresh after the bridge becomes stable.
- Display the current bridge state in the IntelliJ status bar.
- Provide direct recovery and navigation actions from the status widget.

## Non-goals

- Changing the bridge protocol or listener ports.
- Adding authentication.
- Replacing the existing settings UI.
- Managing IntelliJ's built-in MCP server configuration.

## Proposed startup flow

Register a `ProjectActivity` using the `com.intellij.postStartupActivity`
extension point. Its `execute()` method requests `McpBridgeService` and asks it
to refresh. Service creation and refresh must remain safe when invoked more
than once because the application lifecycle listener may also request it.

The existing `AppLifecycleListener` can remain as a fallback for application
frames that exist before a project is fully opened. The project activity is
the primary reliable trigger for normal IDE startup with a project.

```text
IntelliJ application starts
        |
        +-- appFrameCreated() -------------------+
        |                                       |
        +-- project post-startup activity -------+--> get service --> refresh
```

## Adaptive retry

Replace the fixed startup behavior with two refresh intervals:

- `1 second` while enabled but not connected.
- `10 seconds` after listeners and the target MCP server are available.

An explicit restart from settings or the status widget should enqueue an
immediate refresh. Only one refresh may run at a time. Disposal must cancel
pending retries and close all listeners.

Fast retry applies to recoverable startup conditions:

- IntelliJ MCP target has not started yet.
- The selected WSL interface has not appeared yet.

Binding failures and invalid settings should still be shown immediately as
errors. They may continue to be checked at the normal interval, but should not
produce repeated notifications.

## Status model

Expose an explicit service state instead of deriving UI state only from the
current listener list.

| State | Meaning |
| --- | --- |
| `DISABLED` | The bridge is disabled in settings. |
| `STARTING` | Startup or a retry is in progress and no endpoint is ready yet. |
| `CONNECTED` | At least one bridge listener is running and an IntelliJ MCP target is resolved. |
| `ERROR` | Configuration, address selection, or listener binding requires attention. |

The status snapshot should include:

- state
- running listener addresses
- listener port
- resolved IntelliJ MCP target
- last error
- last successful refresh time

State changes should be published through the IntelliJ message bus or a
service-owned listener API. The status bar widget must update only when the
snapshot changes and must perform UI updates on the event dispatch thread.

## Status bar widget

Register a `StatusBarWidgetFactory` and display a compact icon in the lower
right status bar.

| State | Appearance | Tooltip |
| --- | --- | --- |
| `DISABLED` | neutral/gray | `MCP WSL Bridge is disabled` |
| `STARTING` | yellow | `MCP WSL Bridge is starting` |
| `CONNECTED` | green | endpoint and IntelliJ target |
| `ERROR` | red | most recent actionable error |

Use IntelliJ platform icons or theme-aware SVG icons. Do not encode state using
color alone; the tooltip and accessible text must include the state name.

Clicking the widget should open a popup with:

- current state
- WSL-facing endpoint(s)
- resolved IntelliJ MCP target
- last error, when present
- **Restart Bridge**
- **Open Settings**
- **Copy Endpoint**, when connected

The widget is project-visible but reads the application-level bridge service,
so multiple open projects must not create multiple proxies.

## Error handling

- A missing target during startup is `STARTING`, not `ERROR`.
- An empty interface selection is `ERROR` because user action is required.
- A port binding failure is `ERROR` and includes the address and port.
- Losing a previously available IntelliJ MCP target moves the state to
  `STARTING` while retries continue.
- Restart actions should be non-blocking and should immediately show
  `STARTING`.

## Test plan

### Service tests

- The service starts when requested by the project startup activity.
- Repeated lifecycle and project startup calls do not create duplicate
  listeners.
- A target that becomes available after initial failures is detected using the
  fast retry interval.
- The refresh interval returns to the normal interval after connection.
- Restart performs an immediate refresh.
- Disposal cancels pending work and closes listeners.
- Expected status transitions are published exactly once per changed snapshot.

### Plugin descriptor tests

- `postStartupActivity` is registered.
- The application lifecycle listener remains registered.
- The status bar widget factory is registered.

### Widget tests

- Every service state maps to the intended icon, text, and tooltip.
- Restart delegates to `McpBridgeService.restart()`.
- Open Settings navigates to MCP WSL Bridge settings.
- Copy Endpoint is unavailable unless a listener is connected.

### Manual verification

1. Enable both IntelliJ MCP Server and MCP WSL Bridge.
2. Close IntelliJ completely.
3. Start IntelliJ and open the project.
4. Confirm the widget moves from `STARTING` to `CONNECTED`.
5. Call an IntelliJ MCP tool from WSL without opening bridge settings.
6. Stop or disable the built-in MCP server and confirm the widget reflects the
   loss of connectivity.
7. Re-enable it and confirm automatic recovery.
8. Occupy the listener port and confirm the widget shows an actionable error.

## Implementation sequence

1. Introduce the explicit status state and change notification mechanism.
2. Add adaptive startup retry and immediate restart behavior.
3. Add and register the project post-startup activity.
4. Add the status bar widget and popup actions.
5. Add automated tests.
6. Perform the restart-based manual verification above.
