package moe.matsuri.nb4a.proxy.anytls

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex

private val plainSha256Hex = Regex("[0-9a-fA-F]{64}")
private val colonSha256Hex = Regex("(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}")

// Clash fingerprint hashes the complete leaf certificate DER, not its public key.
internal fun anyTLSHexCertificatePin(raw: String): String? {
    val hex = raw.trim()
    return when {
        plainSha256Hex.matches(hex) -> hex.decodeHex().base64()
        colonSha256Hex.matches(hex) -> hex.replace(":", "").decodeHex().base64()
        else -> null
    }
}

internal fun parseAnyTLSClashCertificatePin(raw: Any): String? {
    require(raw is String) { "AnyTLS certificate fingerprint must be a hexadecimal string" }
    if (raw.isBlank()) return null
    return requireNotNull(anyTLSHexCertificatePin(raw)) {
        "Invalid AnyTLS certificate fingerprint: expected a hexadecimal SHA-256 digest"
    }
}

// The bean stores one standard, padded Base64 SHA-256 digest per line. The editor
// also accepts hex, but a malformed nonblank line must never turn into no pin.
internal fun parseAnyTLSCertificatePins(value: String?): List<String> = value.orEmpty()
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { pin ->
        anyTLSHexCertificatePin(pin) ?: run {
            val decoded = if (pin.length == 44) pin.decodeBase64() else null
            require(decoded?.size == 32 && decoded.base64() == pin) {
                "Invalid AnyTLS certificate fingerprint: expected a SHA-256 digest"
            }
            pin
        }
    }
    .toList()
