# Changelog

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
