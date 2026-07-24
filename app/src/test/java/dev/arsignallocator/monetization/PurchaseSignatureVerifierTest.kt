package dev.arsignallocator.monetization

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseSignatureVerifierTest {
    private val verifier = RsaPurchaseVerifier(
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)),
        ),
    )

    @Test
    fun `accepts the exact signed purchase data`() {
        assertTrue(verifier.verify(ORIGINAL_JSON, SIGNATURE))
        assertTrue(playPurchaseVerifier(PUBLIC_KEY).verify(ORIGINAL_JSON, SIGNATURE))
    }

    @Test
    fun `rejects altered data missing signatures and malformed signatures`() {
        assertFalse(verifier.verify(ORIGINAL_JSON.replace("test-token", "other-token"), SIGNATURE))
        assertFalse(verifier.verify(ORIGINAL_JSON, ""))
        assertFalse(verifier.verify(ORIGINAL_JSON, "not base64"))
    }

    @Test
    fun `invalid encoded Play keys fail closed`() {
        listOf("", "not base64", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            .forEach {
                assertFalse(playPurchaseVerifier(it).verify(ORIGINAL_JSON, SIGNATURE))
            }
    }

    companion object {
        const val PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAom6TEH5QNQoqP9JknIr9" +
                "UFgiLEmS7yq/M/ULr6a7DETerRqZcpKBm7Ep4Smp4Mfybh8gD3v5IT9mRZ4syfs" +
                "N7YF4b8iL6bTmFtH+6VQ+cgdGUF1zFNyr8o4SPNBA40je78niJCS11FlvOtRJhu" +
                "j2B15hsxknQMQUSV9ZVwdg/7EmroFlzxHW1e105TSvgezvoQgBfcT9J1hgeWLnX" +
                "q/pMB9a/lAIZDt7qAj2IdbzTsATUq6w4q7xoIIsdwr2jyMMc0ZlbgTSJ6R8Gt3I" +
                "DBP/okHFDfPHgPxpcBEGz7b4s62ERa8Mu0oONn3g8pBtyTXTTsGOxf39d0T9eKC" +
                "tv3BXAwIDAQAB"
        private const val ORIGINAL_JSON =
            """{"productId":"pro_lifetime","purchaseToken":"test-token"}"""
        private const val SIGNATURE =
            "CXT6/EwatACOX7frLLnhDEfvCb1UvtzX1FaUnAa5j5nO8BMWBV79tCz8c9+YGk0I" +
                "5bE2wN8byxzCsy+FHg4NnkrZcAl5chvHPtqrN2CQaEw+WXnMQa5H5dOLMfeutYw" +
                "dOL0e4+G0onMDTbaD4yVFXhs8B60UZ2vD2aX2Ll+IxbGktrKfOauwui92/a+E+m" +
                "ffDX5Tm3N044BA9T+ZCsHqbfuUGRupT0vCAD/P5pGwzRWl3pQTZT/Q6e0I3bdbm8" +
                "2ELqYpX5gRrggUf+dncKqjcaySmesVZ6lBs6BwLcolVtez8M1QCiBHCt4/7YcITY" +
                "Qds4xP37CNan9DRITRyL5EKA=="
    }
}
