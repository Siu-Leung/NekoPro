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
    val presets: List<Triple<String, String, String>> = listOf(
        // category, name, 说明
        Triple("domainset", "cdn", "CDN 加速域名"),
        Triple("domainset", "download", "下载域名"),
        Triple("domainset", "stream", "流媒体域名"),
        Triple("domainset", "apple", "Apple 服务域名"),
        Triple("domainset", "google", "Google 域名"),
        Triple("domainset", "microsoft", "Microsoft 域名"),
        Triple("non_ip", "reject", "广告/跟踪拦截（非IP）"),
        Triple("ip", "private", "私有 IP 段"),
        Triple("ip", "reject", "广告/跟踪拦截（IP）"),
    )

    fun urlOf(category: String, name: String): String =
        "https://ruleset.skk.moe/sing-box/$category/$name.json"

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
