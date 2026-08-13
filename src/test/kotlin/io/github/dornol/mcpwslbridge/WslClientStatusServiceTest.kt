package io.github.dornol.mcpwslbridge

import kotlin.test.Test
import kotlin.test.assertEquals

class WslClientStatusServiceTest {
    @Test
    fun `availability checks are cached and can be forced`() {
        var checks = 0
        val service = WslClientStatusService({ _, _ -> checks++; true }, cacheMillis = 60_000)

        assertEquals(true, service.isInstalled("Ubuntu", "codex"))
        assertEquals(true, service.isInstalled("Ubuntu", "codex"))
        assertEquals(1, checks)
        assertEquals(true, service.status("Ubuntu", "codex", forceRefresh = true).installed)
        assertEquals(2, checks)
    }
}
