package io.github.dornol.mcpwslbridge

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/** Keeps the bridge token out of the plugin XML state. */
class BridgeAuthTokenStore(
    private val serviceName: String = "MCP WSL Bridge",
) {
    @Volatile private var fallback: String = ""
    private val attributes get() = CredentialAttributes(serviceName, "bridge-token")

    fun get(): String = runCatching {
        PasswordSafe.instance.getAsync(attributes).blockingGet(2_000)?.getPasswordAsString().orEmpty()
    }.getOrDefault(fallback)

    fun set(token: String) {
        fallback = token
        runCatching {
            PasswordSafe.instance.set(attributes, if (token.isBlank()) null else Credentials("bridge", token), false)
        }
    }
}
