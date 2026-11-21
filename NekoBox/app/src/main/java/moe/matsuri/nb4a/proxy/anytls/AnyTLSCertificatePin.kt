package moe.matsuri.nb4a.proxy.anytls

import com.google.common.io.BaseEncoding

private const val SHA256_BYTE_LENGTH = 32
private const val SHA256_BASE64_LENGTH = 44

private val STANDARD_BASE64 = BaseEncoding.base64()
private val PLAIN_SHA256_HEX = Regex("[0-9a-fA-F]{64}")
private val COLON_SHA256_HEX = Regex("(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}")

/**
 * Converts a 64-character hex SHA-256 digest (plain or colon-separated) into the
 * padded standard Base64 string expected by sing-box `certificate_sha256`.
 *
 * Uses Guava [BaseEncoding] so encoding works on minSdk 21 without depending on
 * API 26 `java.util.Base64` or transitive okio internals.
 *
 * @param hex Raw hex string to parse.
 * @return Canonical 44-character standard padded Base64 digest, or `null` if [hex] is not valid hex.
 */
private fun sha256HexToBase64(hex: String): String? {
    val stripped = when {
        PLAIN_SHA256_HEX.matches(hex) -> hex
        COLON_SHA256_HEX.matches(hex) -> hex.replace(":", "")
        else -> return null
    }
    val digestBytes = ByteArray(SHA256_BYTE_LENGTH)
    for (byteIndex in 0 until SHA256_BYTE_LENGTH) {
        val highNibble = Character.digit(stripped[byteIndex * 2], 16)
        val lowNibble = Character.digit(stripped[byteIndex * 2 + 1], 16)
        if (highNibble < 0 || lowNibble < 0) return null
        digestBytes[byteIndex] = ((highNibble shl 4) or lowNibble).toByte()
    }
    return STANDARD_BASE64.encode(digestBytes)
}

/**
 * Converts a trimmed hex SHA-256 certificate pin into canonical padded Base64,
 * or returns `null` if [raw] is not a valid hex SHA-256 digest.
 */
internal fun anyTLSHexCertificatePin(raw: String): String? = sha256HexToBase64(raw.trim())

/**
 * Validates and converts a Clash YAML AnyTLS `fingerprint` value into canonical Base64.
 *
 * @param raw Raw YAML field value.
 * @return Canonical Base64 SHA-256 digest, or `null` when [raw] is a blank string.
 * @throws IllegalArgumentException if [raw] is not a String or is not a valid hex SHA-256 digest.
 */
internal fun parseAnyTLSClashCertificatePin(raw: Any): String? {
    require(raw is String) { "AnyTLS certificate fingerprint must be a hexadecimal string" }
    if (raw.isBlank()) return null
    return requireNotNull(anyTLSHexCertificatePin(raw)) {
        "Invalid AnyTLS certificate fingerprint: expected a hexadecimal SHA-256 digest"
    }
}

/**
 * Parses a newline-delimited list of AnyTLS certificate SHA-256 pins (accepting both
 * canonical 44-char padded Base64 and plain/colon-separated hex).
 *
 * Any malformed non-blank line throws [IllegalArgumentException] rather than silently
 * degrading into an unpinned configuration.
 *
 * @param value Raw multiline pin string from bean storage or editor input.
 * @return List of canonical padded Base64 SHA-256 digests.
 * @throws IllegalArgumentException if any non-blank line is not a valid 32-byte SHA-256 digest.
 */
internal fun parseAnyTLSCertificatePins(value: String?): List<String> = value.orEmpty()
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { pin ->
        anyTLSHexCertificatePin(pin) ?: run {
            val decoded = if (pin.length == SHA256_BASE64_LENGTH) {
                runCatching { STANDARD_BASE64.decode(pin) }.getOrNull()
            } else {
                null
            }
            require(decoded?.size == SHA256_BYTE_LENGTH && STANDARD_BASE64.encode(decoded) == pin) {
                "Invalid AnyTLS certificate fingerprint: expected a SHA-256 digest"
            }
            pin
        }
    }
    .toList()
