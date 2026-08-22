package io.nekohasekai.sagernet.ktx

fun Map<*, *>?.safeString(key: String, default: String = ""): String {
    if (this == null) return default
    val value = this[key] ?: return default
    return value.toString()
}

fun Map<*, *>?.safeInt(key: String, default: Int = 0): Int {
    if (this == null) return default
    val value = this[key] ?: return default
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

fun Map<*, *>?.safeBoolean(key: String, default: Boolean = false): Boolean {
    if (this == null) return default
    val value = this[key] ?: return default
    return when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> default
    }
}
