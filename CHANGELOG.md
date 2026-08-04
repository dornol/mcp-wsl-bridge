# Changelog

## 0.1.10 - 2026-08-03

- Preserve loopback Origin and Streamable HTTP response framing for IntelliJ MCP approval flows.
- Preserve MCP session and SSE approval exchanges through the WSL bridge.
- Add safe IntelliJ bridge diagnostics for request headers, sessions, responses, SSE messages, and timings.
- Add regression coverage for server-initiated approval requests and approval responses.
- Detect IntelliJ MCP restarts from the configured port and recycle the bridge endpoint for new client sessions.
- Virtualize MCP session IDs and recreate IntelliJ upstream sessions after restarts or connection failures.
- Keep bridge listeners alive during transient IntelliJ MCP target loss to reduce unnecessary SSE disconnects.
- Note: Claude Code may require `/mcp reconnect` after an IntelliJ restart when its elicitation state is not recovered.

## 0.1.9 - 2026-07-31

- Add multi-server MCP routing, including the IDE Index MCP preset.
- Add WSL client configuration and removal actions for Codex, Claude Code, and GitHub Copilot CLI.
- Redesign MCP server settings with a native list, detail panel, templates, and popup editing.
- Improve bridge status reporting, automatic WSL endpoint refresh, and HTTP routing compatibility.
- Verify compatibility against IntelliJ IDEA 2025.2 through 2026.1.

## 0.1.7 - 2026-07-27

- Use the public application-frame lifecycle callback for automatic startup.

## 0.1.6 - 2026-07-27

- Replace deprecated and internal IntelliJ startup, settings-path, and clipboard APIs.

## 0.1.5 - 2026-07-27

- Start the bridge after IntelliJ initialization without using the deprecated service preload attribute.

## 0.1.4 - 2026-07-27

- Start the bridge automatically when enabled in IntelliJ.
- Rebind listeners automatically when selected WSL interface addresses change.
- Refresh Codex, Claude Code, and GitHub Copilot CLI endpoints across configured WSL distributions.
- Add unit and socket integration tests for bridge startup, relay, configuration, and failure paths.

## 0.1.3 - 2026-07-25

- Prevent shell startup scripts from blocking WSL client configuration by using a non-interactive login shell.
- Show immediate progress and explicit success, failure, and timeout feedback for **Apply to WSL**.
- Load WSL distributions and restart the bridge outside IntelliJ's UI thread.

## 0.1.2 - 2026-07-24

- Use a Windows HTTP reverse proxy as the primary WSL client endpoint.
- Configure Claude Code and Codex to connect directly to the selected Windows NIC address.
- Remove the WSL-local Node proxy requirement.
- Rebind selected network interfaces after their IP addresses change.

## 0.1.1 - 2026-07-24

- Start a WSL loopback relay automatically when configuring Codex or Claude Code.
- Fix Claude Code HTTP 403 errors caused by IntelliJ MCP loopback validation.
- Run WSL client configuration commands through the user's login shell.

## 0.1.0 - 2026-07-23

- Proxy IntelliJ's loopback MCP server through selected Windows NIC addresses for WSL clients.
- Discover the current IntelliJ MCP port automatically, with a manual override.
- Rewrite HTTP/1.1 `Host` headers so WSL-facing requests satisfy IntelliJ MCP loopback validation.
- Configure Codex and Claude Code in a selected WSL distribution.
