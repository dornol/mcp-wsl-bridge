# Privacy

MCP WSL Bridge does not collect, transmit, or sell telemetry or personal data.

The plugin runs inside the local IntelliJ IDE and opens listeners only on IPv4 addresses explicitly selected by the user. It relays MCP traffic to the built-in IntelliJ MCP server through `127.0.0.1`.

When the user clicks **Apply to WSL** for Codex or Claude Code, the plugin starts `wsl.exe` and invokes the selected agent's local MCP configuration command. It does not read, upload, or send WSL agent configuration files to any remote service.

The user is responsible for choosing safe network interfaces. Selecting a LAN, Wi-Fi, Ethernet, or VPN address can expose the IDE MCP endpoint to that network.
