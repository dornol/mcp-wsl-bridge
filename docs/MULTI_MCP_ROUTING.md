# Multi-MCP Routing

MCP WSL Bridge can expose multiple HTTP MCP servers through one WSL-facing
listener. Each server keeps its own MCP connection and tool namespace; the
bridge only routes HTTP requests by path.

## Routing model

The bridge listens on one selected Windows/WSL interface and one HTTP port.
Each configured server has one MCP path. The bridge uses that path on both the
WSL-facing endpoint and the local target endpoint:

```text
/stream                         -> http://127.0.0.1:64342/stream
/index-mcp/streamable-http      -> http://127.0.0.1:29170/index-mcp/streamable-http
```

From WSL, clients use the bridge address and the same path:

```text
http://<WSL-gateway>:64343/stream
http://<WSL-gateway>:64343/index-mcp/streamable-http
```

The bridge forwards HTTP methods, query strings, request headers, response
headers, and streaming response bodies. MCP servers are not merged and the
bridge does not inspect or rename MCP tools.

## IDE Index MCP Server example

The IDE Index MCP Server uses Streamable HTTP. Its IntelliJ IDEA default is
port `29170` and its primary endpoint is:

```text
http://127.0.0.1:29170/index-mcp/streamable-http
```

Configure it with the MCP path `/index-mcp/streamable-http`. The IntelliJ
built-in MCP uses `/stream` by default.

## Routing rules

- MCP paths must begin with `/` and be unique.
- The longest matching path wins.
- The same MCP path is forwarded to the selected target server.
- Query strings are preserved.
- Unknown paths return HTTP 404.
- A target connection failure returns HTTP 502.
- Only the selected network interfaces accept bridge connections.

The raw TCP compatibility listener remains available at the configured
listener port plus one for the legacy single-target flow. Multi-server path
routing uses the HTTP listener itself.

## Client configuration

Clients should register each MCP server as a separate server. For example:

```json
{
  "mcpServers": {
    "intellij": {
      "url": "http://<WSL-gateway>:64343/stream"
    },
    "intellij-index": {
      "url": "http://<WSL-gateway>:64343/index-mcp/streamable-http"
    }
  }
}
```

This keeps tool discovery and failures isolated per MCP server while requiring
only one Windows-facing bridge port.

In the bridge settings, use the server table to add one row per MCP server.
Choose `intellij` for the built-in server or `http` for another HTTP server,
then edit the server ID/name, MCP path, target host/port, and mode directly in
the table. The built-in server uses automatic port detection when its mode is
`auto`.

## Implementation status

The route model, path rewriting, one-port HTTP routing, settings editor,
legacy settings fallback, multi-endpoint WSL client configuration, and routing
tests are implemented. Windows/WSL integration testing with multiple real MCP
servers remains part of release verification.
