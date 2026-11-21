package moe.matsuri.nb4a.proxy.anytls

import java.util.Base64

private val plainSha256Hex = Regex("[0-9a-fA-F]{64}")
private val colonSha256Hex = Regex("(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}")

// Convert a hex digest (plain or colon-separated) into raw bytes, then to the padded
// standard Base64 form expected by sing-box `certificate_sha256`.
// java.util.Base64 is used deliberately: available on minSdk 21 and not subject to R8 renaming
// of a transitive okio companion method (which broke the release unit test at runtime).
private fun sha256HexToBase64(hex: String): String? {
    val stripped = when {
        plainSha256Hex.matches(hex) -> hex
        colonSha256Hex.matches(hex) -> hex.replace(":", "")
        else -> return null
    }
    val bytes = ByteArray(32)
    var i = 0
    while (i < 32) {
        val hi = Character.digit(stripped[i * 2], 16)
        val lo = Character.digit(stripped[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        bytes[i] = ((hi shl 4) or lo).toByte()
        i++
    }
    return Base64.getEncoder().encodeToString(bytes)
}

internal fun anyTLSHexCertificatePin(raw: String): String? = sha256HexToBase64(raw.trim())

internal fun parseAnyTLSClashCertificatePin(raw: Any): String? {
    require(raw is String) { "AnyTLS certificate fingerprint must be a hexadecimal string" }
    if (raw.isBlank()) return null
    return requireNotNull(anyTLSHexCertificatePin(raw)) {
        "Invalid AnyTLS certificate fingerprint: expected a hexadecimal SHA-256 digest"
    }
}

// The bean stores one standard padded Base64 SHA-256 digest per line. The editor also accepts
// hex, but a malformed nonblank line must never silently degrade into "no pin".
internal fun parseAnyTLSCertificatePins(value: String?): List<String> = value.orEmpty()
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { pin ->
        anyTLSHexCertificatePin(pin) ?: run {
            val decoded = if (pin.length == 44) runCatching { Base64.getDecoder().decode(pin) }.getOrNull() else null
            require(decoded?.size == 32 && Base64.getEncoder().encodeToString(decoded) == pin) {
                "Invalid AnyTLS certificate fingerprint: expected a SHA-256 digest"
            }
            pin
        }
    }
    .toList()
