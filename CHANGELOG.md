# Changelog

## 0.1.0 - 2026-07-23

- Proxy IntelliJ's loopback MCP server through selected Windows NIC addresses for WSL clients.
- Discover the current IntelliJ MCP port automatically, with a manual override.
- Rewrite HTTP/1.1 `Host` headers so WSL-facing requests satisfy IntelliJ MCP loopback validation.
- Configure Codex and Claude Code in a selected WSL distribution.
