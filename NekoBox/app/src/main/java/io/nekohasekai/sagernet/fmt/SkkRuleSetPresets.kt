package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions.RuleSet

/**
 * 远程规则集（SukkaW / Surge）预设生成。
 *
 * skk 规则集（source 格式 JSON）：
 *   https://ruleset.skk.moe/sing-box/{domainset|non_ip|ip}/<name>.json
 *
 * DataStore.skkRuleSets 存 JSON：[{"category":"domainset","name":"cdn","enabled":true,"outbound":"proxy"}]
 * tag 约定：skk-<category>-<name>（与 UI 层约定一致，避免冲突）。
 */
data class SkkRuleSetItem(
    val category: String,
    val name: String,
    val enabled: Boolean = false,
    val outbound: String = "proxy",
)

object SkkRuleSetPresets {

    // 内置清单（顺序即匹配优先级）：domainset → non_ip → ip
    // 完整清单已全部验证 200 OK（2026-08-23）
    val presets: List<Triple<String, String, String>> = listOf(
        // domainset（仅域名，不触发 DNS）
        Triple("domainset", "cdn", "CDN 加速域名"),
        Triple("domainset", "download", "下载域名"),
        Triple("domainset", "reject", "广告/跟踪拦截（域名集）"),
        Triple("domainset", "reject_extra", "额外拦截域名"),
        Triple("domainset", "game-download", "游戏下载域名"),
        Triple("domainset", "speedtest", "测速域名"),
        // non_ip（不触发 DNS）
        Triple("non_ip", "cdn", "CDN（含IP类）"),
        Triple("non_ip", "download", "下载（含IP类）"),
        Triple("non_ip", "reject", "广告/跟踪拦截（非IP）"),
        Triple("non_ip", "apple_services", "Apple 服务"),
        Triple("non_ip", "apple_cn", "Apple 国区"),
        Triple("non_ip", "apple_intelligence", "Apple Intelligence"),
        Triple("non_ip", "ai", "AI 服务"),
        Triple("non_ip", "direct", "直连规则"),
        Triple("non_ip", "domestic", "国内规则"),
        Triple("non_ip", "global", "全球规则"),
        Triple("non_ip", "microsoft", "Microsoft 服务"),
        Triple("non_ip", "telegram", "Telegram"),
        Triple("non_ip", "neteasemusic", "网易云音乐"),
        Triple("non_ip", "lan", "局域网"),
        // ip（触发 DNS）
        Triple("ip", "cdn", "CDN（IP）"),
        Triple("ip", "download", "下载（IP）"),
        Triple("ip", "reject", "广告/跟踪拦截（IP）"),
        Triple("ip", "apple_services", "Apple 服务（IP）"),
        Triple("ip", "ai", "AI 服务（IP）"),
        Triple("ip", "domestic", "国内（IP）"),
        Triple("ip", "telegram", "Telegram（IP）"),
        Triple("ip", "neteasemusic", "网易云音乐（IP）"),
        Triple("ip", "lan", "局域网（IP）"),
    )

    fun baseUrl(): String =
        DataStore.skkBaseUrl.trimEnd('/').ifBlank { "https://ruleset.skk.moe/sing-box" }

    fun urlOf(category: String, name: String): String =
        "${baseUrl()}/$category/$name.json"

    fun tagOf(category: String, name: String): String = "skk-$category-$name"

    fun loadItems(): List<SkkRuleSetItem> {
        return try {
            val type = object : TypeToken<List<SkkRuleSetItem>>() {}.type
            Gson().fromJson<List<SkkRuleSetItem>>(DataStore.skkRuleSets, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveItems(items: List<SkkRuleSetItem>) {
        DataStore.skkRuleSets = Gson().toJson(items)
    }

    /**
     * 生成远程 rule-set 配置项（type=remote, format=source）。
     * 仅返回用户已启用（enabled=true）的项，且仅存在于内置清单中的条目。
     */
    fun buildEnabledRuleSets(): List<Pair<RuleSet, SkkRuleSetItem>> {
        val items = loadItems()
        val enabledNames = presets.map { it.first to it.second }.toSet()
        return items.filter { it.enabled && (it.category to it.name) in enabledNames }
            .sortedBy { presetOrderOf(it.category) }
            .map { item ->
                val rs = RuleSet().apply {
                    type = "remote"
                    tag = tagOf(item.category, item.name)
                    format = "source"
                    url = urlOf(item.category, item.name)
                    update_interval = "12h"
                    download_detour = item.outbound.ifBlank { "proxy" }
                }
                rs to item
            }
    }

    private fun presetOrderOf(category: String): Int = when (category) {
        "domainset" -> 0
        "non_ip" -> 1
        "ip" -> 2
        else -> 9
    }
}
