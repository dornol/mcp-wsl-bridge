# MCP WSL Bridge

An IntelliJ Platform plugin that exposes the IDE's loopback-only MCP server to WSL without listening on every Windows network interface.

## What it does

`MCP WSL Bridge` listens only on the IPv4 addresses selected in **Settings | Tools | MCP WSL Bridge** (normally the `vEthernet (WSL)` address). It transparently proxies every TCP connection to IntelliJ's active MCP server on `127.0.0.1`.

For HTTP/1.1 clients, the bridge rewrites the initial `Host` header to the loopback MCP address. This is necessary because the built-in MCP server rejects requests whose Host header names the WSL-facing adapter.

The plugin detects the port saved by the built-in IntelliJ MCP Server and falls back to checking ports beginning at `64342`. A manual target override is available for unusual configurations.

## Setup

1. Enable IntelliJ's built-in MCP server in **Settings | Tools | MCP Server**.
2. Open **Settings | Tools | MCP WSL Bridge**.
3. Select the `vEthernet (WSL)` IPv4 address (the plugin marks likely WSL interfaces).
4. Enable the bridge and apply settings. The default listener port is `64343`.
5. In the **WSL Client Configuration** section, choose a WSL distribution and select either **Codex** or **Claude Code**. **Apply to WSL** uses the selected agent's CLI to create or replace the `intellij-wsl-bridge` user configuration. The **Others** tab copies a generic streamable HTTP JSON entry.
6. In WSL, use its default gateway as the Windows host IP:

   ```sh
   ip route show default | awk '{print $3}'
   ```

7. Configure another client with the displayed address and the MCP endpoint appropriate for your IntelliJ version, for example `http://<gateway>:64343/stream`.

## Security

Do not select a Wi-Fi, Ethernet, or VPN address unless you intend to expose the MCP server on that network. This first release has no authentication; optional token authentication is planned for a later release.

## Development

Requires JDK 21 or newer. Run `./gradlew build` to build and `./gradlew runIde` to launch a sandbox IDE.
