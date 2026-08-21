package io.github.cctyl.keydroidx.music.network.crypto

import org.junit.Test
import org.junit.Assert.*

class EapiCryptoTest {
    @Test
    fun testEncryption() {
        val url = "/eapi/search"
        val payload = mapOf("s" to "周杰伦", "type" to 1)
        val encrypted = EapiCrypto.encryptParams(url, payload)
        
        assertNotNull(encrypted)
        assertTrue(encrypted.isNotEmpty())
        // 简单验证加密内容，确保不为空
        println("Encrypted data: " + encrypted)
    }
}
