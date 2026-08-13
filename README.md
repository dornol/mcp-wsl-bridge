# MCP WSL Bridge

An IntelliJ Platform plugin that exposes the IDE's loopback-only MCP server to WSL without listening on every Windows network interface.

## What it does

`MCP WSL Bridge` listens only on the IPv4 addresses selected in **Settings | Tools | MCP WSL Bridge** (normally the `vEthernet (WSL)` address). Its HTTP reverse proxy forwards MCP requests to IntelliJ's active loopback-only MCP server.

The bridge creates a fresh loopback HTTP request to the IDE, so Claude Code and Codex can connect directly from WSL without a separate WSL-side proxy process.

Multiple HTTP MCP servers can be exposed through the same bridge port using
different paths. See [Multi-MCP Routing](docs/MULTI_MCP_ROUTING.md) for the
routing model and the IDE Index MCP Server example.

The plugin detects the port saved by the built-in JetBrains MCP Server and falls back to checking a range of loopback ports, which covers dynamically allocated RustRover ports. The IDE Index MCP route likewise probes around its IntelliJ default (`29170`). A manual target override is available for unusual configurations. WSL registrations use an IDE-specific name such as `intellij-wsl-bridge` or `rustrover-wsl-bridge`.

## Setup

1. Enable IntelliJ's built-in MCP server in **Settings | Tools | MCP Server**.
2. Open **Settings | Tools | MCP WSL Bridge**.
3. Select the `vEthernet (WSL)` IPv4 address (the plugin marks likely WSL interfaces).
4. Enable the bridge and apply settings. The default listener port is `64343`.
5. The built-in **JetBrains MCP** route is always present and auto-detects its port. To add the IDE Index MCP route, click **Add IDE Index MCP**. Its MCP path is `/index-mcp/streamable-http`, target host is `127.0.0.1`, and the bridge probes around the default port `29170`.
6. In the **WSL Client Configuration** section, choose a WSL distribution and select either **Codex**, **Claude Code**, or **GitHub Copilot CLI**. The panel shows CLI availability, provides **Test connection**, and **Apply to WSL** registers every enabled route as a separate MCP server. The **Others** tab copies a generic multi-server streamable HTTP JSON entry.
7. In WSL, use its default gateway as the Windows host IP:

   ```sh
   ip route show default | awk '{print $3}'
   ```

8. Configure another client with the displayed route URLs, for example `http://<gateway>:64343/index-mcp/streamable-http`.

The bridge can optionally require a generated token for WSL HTTP endpoints. The token is stored locally and appended to generated client URLs. Imported WSL client history can be cleared with **Reset imported WSL history**; this does not remove entries already written to a WSL client.

## Security

Do not select a Wi-Fi, Ethernet, or VPN address unless you intend to expose the MCP server on that network. This first release has no authentication; optional token authentication is planned for a later release.

See [Privacy](PRIVACY.md) for local-data handling and [Publishing](PUBLISHING.md) for Marketplace release preparation.

## Documentation map

- [Automatic Startup and Status Widget](docs/STARTUP_AND_STATUS_WIDGET.md) — startup reliability, adaptive retry, service states, and status bar widget plan.
- [IntelliJ API Compatibility and Marketplace Review](docs/PLUGIN_API_COMPATIBILITY.md) — public API policy, verifier checklist, and release review requirements.
- [Publishing](PUBLISHING.md) — Marketplace release preparation and publishing workflow.
- [Multi-MCP Routing](docs/MULTI_MCP_ROUTING.md) — server profiles, path routing, and client configuration.
- [Privacy](PRIVACY.md) — local-data handling and privacy notes.

## License

MIT. See [LICENSE](LICENSE).

## Development

Requires JDK 21 or newer. Run `./gradlew build` to build and `./gradlew runIde` to launch a sandbox IDE.

The planned automatic-startup hardening and IntelliJ status bar indicator are
described in [Automatic Startup and Status Widget](docs/STARTUP_AND_STATUS_WIDGET.md).
