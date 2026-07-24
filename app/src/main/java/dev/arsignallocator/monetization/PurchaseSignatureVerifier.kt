package dev.arsignallocator.monetization

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64

fun interface PurchaseVerifier {
    fun verify(originalJson: String, signature: String): Boolean
}

class RsaPurchaseVerifier(
    private val publicKey: PublicKey,
) : PurchaseVerifier {
    override fun verify(originalJson: String, signature: String): Boolean {
        if (originalJson.isBlank() || signature.isBlank()) return false
        return runCatching {
            Signature.getInstance("SHA1withRSA").run {
                initVerify(publicKey)
                update(originalJson.toByteArray(StandardCharsets.UTF_8))
                verify(Base64.decode(signature))
            }
        }.getOrDefault(false)
    }
}

fun playPurchaseVerifier(encodedPublicKey: String): PurchaseVerifier {
    val key = runCatching {
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(encodedPublicKey)),
        )
    }.getOrNull()
    return if (key == null) PurchaseVerifier { _, _ -> false } else RsaPurchaseVerifier(key)
}
