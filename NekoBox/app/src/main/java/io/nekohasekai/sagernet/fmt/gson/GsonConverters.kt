package io.nekohasekai.sagernet.fmt.gson

import androidx.room.TypeConverter
import moe.matsuri.nb4a.utils.JavaUtil

object GsonConverters {
    @TypeConverter
    @JvmStatic
    fun toJson(value: Any?): String {
        if (value is Collection<*> && value.isEmpty()) return ""
        return JavaUtil.gson.toJson(value)
    }

    @TypeConverter
    @JvmStatic
    fun toList(value: String?): List<*> {
        if (value.isNullOrBlank()) return emptyList<Any>()
        return JavaUtil.gson.fromJson(value, List::class.java)
    }

    @TypeConverter
    @JvmStatic
    fun toSet(value: String?): Set<*> {
        if (value.isNullOrBlank()) return emptySet<Any>()
        return JavaUtil.gson.fromJson(value, Set::class.java)
    }
}
