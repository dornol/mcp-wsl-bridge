package io.github.dornol.mcpwslbridge

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class VirtualMcpSession(
    val virtualId: String,
    val initializeBody: ByteArray,
    @Volatile var upstreamId: String?,
)

/** Persists client initialize requests while keeping IntelliJ's session ID private to the proxy. */
class VirtualMcpSessionStore(
    private val directory: Path,
) {
    private val log = Logger.getInstance(VirtualMcpSessionStore::class.java)
    private val sessions = ConcurrentHashMap<String, VirtualMcpSession>()

    init {
        load()
    }

    fun find(virtualId: String): VirtualMcpSession? = sessions[virtualId]

    fun create(initializeBody: ByteArray, upstreamId: String): VirtualMcpSession {
        val session = VirtualMcpSession(UUID.randomUUID().toString(), initializeBody.copyOf(), upstreamId)
        sessions[session.virtualId] = session
        save(session)
        return session
    }

    fun rebind(session: VirtualMcpSession, upstreamId: String) {
        session.upstreamId = upstreamId
        save(session)
    }

    private fun load() {
        if (!Files.isDirectory(directory)) return
        runCatching {
            Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".mcp-session") }.forEach { path ->
                    runCatching {
                        val values = Files.readAllLines(path).associate { line ->
                            val separator = line.indexOf('=')
                            line.substring(0, separator) to line.substring(separator + 1)
                        }
                        val virtualId = values["virtualId"] ?: return@runCatching
                        val encoded = values["initialize"] ?: return@runCatching
                        sessions[virtualId] = VirtualMcpSession(
                            virtualId,
                            Base64.getDecoder().decode(encoded),
                            null,
                        )
                    }.onFailure { error -> log.warn("Failed to load virtual MCP session $path", error) }
                }
            }
        }.onFailure { error -> log.warn("Failed to load virtual MCP sessions", error) }
    }

    private fun save(session: VirtualMcpSession) {
        runCatching {
            Files.createDirectories(directory)
            val path = directory.resolve("${session.virtualId}.mcp-session")
            val content = "virtualId=${session.virtualId}\n" +
                "initialize=${Base64.getEncoder().encodeToString(session.initializeBody)}\n"
            FileUtil.writeToFile(path.toFile(), content)
        }.onFailure { error -> log.warn("Failed to persist virtual MCP session", error) }
    }
}
