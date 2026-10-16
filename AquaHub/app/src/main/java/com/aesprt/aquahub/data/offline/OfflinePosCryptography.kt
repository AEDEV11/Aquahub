package com.aesprt.aquahub.data.offline

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aesprt.aquahub.BuildConfig
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class VerifiedOfflineToken(val header: JsonObject, val claims: JsonObject)

class OfflinePosCryptography(
    private val publicKeyDerBase64: String = BuildConfig.OFFLINE_POS_VERIFY_PUBLIC_KEY
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun verifyServerToken(compactJws: String): VerifiedOfflineToken {
        val parts = compactJws.split('.')
        require(parts.size == 3) { "Offline authorization is malformed" }
        val header = json.parseToJsonElement(decodeUrl(parts[0]).toString(StandardCharsets.UTF_8)).jsonObject
        require(header["alg"]?.toString()?.trim('"') == "ES256") { "Offline authorization algorithm is unsupported" }
        require(publicKeyDerBase64.isNotBlank()) { "Offline authorization verification key is not configured" }
        val keyBytes = Base64.getDecoder().decode(publicKeyDerBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        require(verifier.verify(rawEcdsaToDer(decodeUrl(parts[2]))) ) { "Offline authorization signature is invalid" }
        val claims = json.parseToJsonElement(decodeUrl(parts[1]).toString(StandardCharsets.UTF_8)).jsonObject
        return VerifiedOfflineToken(header, claims)
    }

    fun digestHex(canonicalPayload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun serverVerificationKeyFingerprint(): String {
        require(publicKeyDerBase64.isNotBlank()) { "Offline authorization verification key is not configured" }
        return MessageDigest.getInstance("SHA-256")
            .digest(Base64.getDecoder().decode(publicKeyDerBase64))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun deviceIdentity(context: Context): OfflineDeviceIdentity {
            val pair = getOrCreateDeviceKeyPair()
            val publicDer = pair.public.encoded
            val hash = MessageDigest.getInstance("SHA-256").digest(publicDer)
            val deviceId = hash.take(20).joinToString("") { "%02x".format(it) }
            val publicKey = Base64.getEncoder().encodeToString(publicDer)
            val bootCount = runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            }.getOrNull()
            return OfflineDeviceIdentity(deviceId, publicKey, pair, bootCount)
        }

        fun signDevicePayloadDigest(identity: OfflineDeviceIdentity, digestHex: String): String {
            require(digestHex.matches(Regex("[0-9a-f]{64}"))) { "Payload digest is invalid" }
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(identity.keyPair.private)
            signer.update(digestHex.toByteArray(StandardCharsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        }

        private fun getOrCreateDeviceKeyPair(): KeyPair {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "aquahub.offline.pos.device"
            if (keyStore.containsAlias(alias)) {
                val certificate = keyStore.getCertificate(alias)
                    ?: error("Offline device key certificate is unavailable")
                val privateKey = keyStore.getKey(alias, null) as? java.security.PrivateKey
                    ?: error("Offline device private key is unavailable")
                return KeyPair(certificate.publicKey, privateKey)
            }
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            return generator.generateKeyPair()
        }

        /** Converts the fixed-width JOSE ECDSA R||S signature to JCA DER encoding. */
        internal fun rawEcdsaToDer(raw: ByteArray): ByteArray {
            require(raw.size == 64) { "ES256 signature must be 64 bytes" }
            fun integer(bytes: ByteArray): ByteArray {
                var first = 0
                while (first < bytes.lastIndex && bytes[first] == 0.toByte()) first++
                val body = bytes.copyOfRange(first, bytes.size)
                val needsSignByte = body[0].toInt() and 0x80 != 0
                val content = if (needsSignByte) byteArrayOf(0) + body else body
                return byteArrayOf(0x02, content.size.toByte()) + content
            }
            val r = integer(raw.copyOfRange(0, 32))
            val s = integer(raw.copyOfRange(32, 64))
            val content = r + s
            return byteArrayOf(0x30, content.size.toByte()) + content
        }

        private fun decodeUrl(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }
}

data class OfflineDeviceIdentity(
    val deviceId: String,
    val publicKeyDerBase64: String,
    val keyPair: KeyPair,
    val bootCount: Int?
)
