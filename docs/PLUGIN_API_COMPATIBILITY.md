# IntelliJ API Compatibility and Marketplace Review

## Policy

MCP WSL Bridge must use public IntelliJ Platform APIs and documented extension
points only.

The release target is:

- `0` internal API usages
- `0` deprecated API usages
- no unnecessary non-dynamic extensions
- no reliance on implementation details of IntelliJ's bundled MCP server

`@ApiStatus.Internal`, `@IntellijInternalApi`, and APIs marked
`@Deprecated(forRemoval = true)` are not acceptable in production code. They
may compile against the current IDE and still fail or change behavior in a
future IDE release.

## Startup implementation

The bridge must not use any of the following for automatic startup:

- `preload="true"` on an application service
- `ApplicationInitializedListener`
- `AppLifecycleListener.appStarted()`
- other internal or deprecated startup callbacks

The current supported fallback is the public
`AppLifecycleListener.appFrameCreated()` callback. The planned project-level
startup trigger is a documented `ProjectActivity` registered through
`com.intellij.postStartupActivity`.

Both triggers must be idempotent: they may request the same application
service more than once, but must never create duplicate listeners or refresh
workers.

## API review checklist

Before merging IntelliJ Platform changes:

1. Check new imports and method calls against the target platform's API
   annotations.
2. Prefer documented public replacements over `@ApiStatus.Internal`,
   `@Experimental`, `@Obsolete`, or deprecated APIs.
3. Check the extension point's dynamic-plugin status. Do not add a non-dynamic
   extension unless an IDE restart requirement is intentional and documented.
4. Run the complete test suite and `buildPlugin`.
5. Run Plugin Verifier for the target platform and inspect Marketplace
   `Problems` for every supported IDE build.
6. Resolve all internal and deprecated API findings before publishing.

Marketplace `Compatible` means the plugin can be installed for that IDE build;
it does not mean the compatibility report is clean. A release is considered
clean only when the report has no internal or deprecated API findings.

## Current known constraints

- The bridge is an application-level service, so multiple open projects must
  share one proxy instance.
- The bridge may start before IntelliJ's built-in MCP server. Missing MCP during
  this startup window should be treated as `STARTING`, with a short retry
  interval, not as a permanent error.
- The user setting `enabled` remains the authority. Startup triggers must not
  start the bridge when it is disabled.

## References

- [Internal API Migration](https://plugins.jetbrains.com/docs/intellij/api-internal.html)
- [Plugin Compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)
- [Extensions](https://plugins.jetbrains.com/docs/intellij/plugin-extensions.html)
- [Extension Point and Listener List](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html)
- [Automatic Startup and Status Widget](STARTUP_AND_STATUS_WIDGET.md)
