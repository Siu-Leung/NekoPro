package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutRuleSetLibraryBinding
import io.nekohasekai.sagernet.databinding.LayoutRuleSetLibraryItemBinding
import io.nekohasekai.sagernet.fmt.SkkRuleSetItem
import io.nekohasekai.sagernet.fmt.SkkRuleSetPresets
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.app

/**
 * 规则集库：启用 SukkaW/Surge 远程规则集并绑定出站。
 * 数据经 SkkRuleSetPresets.loadItems()/saveItems() 持久化到 DataStore，
 * ConfigBuilder 侧（SkkRuleSetPresets.buildEnabledRuleSets）生成远程 rule-set。
 *
 * 出站选项：proxy（代理，默认）/ direct（直连）。
 */
class RuleSetLibraryActivity : ThemedActivity() {

    lateinit var layout: LayoutRuleSetLibraryBinding
    lateinit var adapter: RuleSetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutRuleSetLibraryBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.ruleset_library)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.ruleSetList.layoutManager = FixedLinearLayoutManager(binding.ruleSetList)
        adapter = RuleSetAdapter()
        binding.ruleSetList.adapter = adapter

        binding.saveButton.setOnClickListener {
            adapter.save()
            snackbar(R.string.ruleset_saved).show()
            finish()
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }

    data class State(val enabled: Boolean, val outbound: String)

    inner class RuleSetAdapter : RecyclerView.Adapter<RuleSetHolder>() {

        val items = ArrayList<Pair<Triple<String, String, String>, State>>()

        init {
            reload()
        }

        fun reload() {
            val saved = SkkRuleSetPresets.loadItems().associateBy { it.category to it.name }
            items.clear()
            SkkRuleSetPresets.presets.forEach { preset ->
                val key = preset.first to preset.second
                val s = saved[key]
                items.add(preset to State(s?.enabled ?: false, s?.outbound ?: "proxy"))
            }
            notifyDataSetChanged()
        }

        fun save() {
            val list = items.map { (preset, state) ->
                SkkRuleSetItem(
                    category = preset.first,
                    name = preset.second,
                    enabled = state.enabled,
                    outbound = state.outbound,
                )
            }
            SkkRuleSetPresets.saveItems(list)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleSetHolder {
            return RuleSetHolder(LayoutRuleSetLibraryItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RuleSetHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    inner class RuleSetHolder(val binding: LayoutRuleSetLibraryItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: Pair<Triple<String, String, String>, State>) {
            val (category, name, desc) = entry.first
            val state = entry.second

            binding.ruleSetName.text = name
            binding.ruleSetCategory.text = when (category) {
                "domainset" -> getString(R.string.ruleset_cat_domainset)
                "non_ip" -> getString(R.string.ruleset_cat_non_ip)
                "ip" -> getString(R.string.ruleset_cat_ip)
                else -> category
            }
            binding.ruleSetDesc.text = desc
            binding.ruleSetOutbound.text = state.outbound

            // 先移除监听器再设置 isChecked，避免 setChecked 触发回调 → notifyItemChanged
            // 在 RecyclerView layout 过程中调用导致崩溃（IllegalStateException）
            binding.ruleSetSwitch.setOnCheckedChangeListener(null)
            binding.ruleSetSwitch.isChecked = state.enabled
            binding.ruleSetSwitch.setOnCheckedChangeListener { _, isChecked ->
                updateState { it.copy(enabled = isChecked) }
            }

            binding.ruleSetOutbound.setOnClickListener {
                showOutboundDialog()
            }
        }

        private fun updateState(transform: (State) -> State) {
            val index = bindingAdapterPosition
            if (index < 0) return
            val old = adapter.items[index].second
            adapter.items[index] = adapter.items[index].first to transform(old)
            adapter.notifyItemChanged(index)
        }

        private fun showOutboundDialog() {
            val options = arrayOf(app.getString(R.string.route_proxy), app.getString(R.string.route_bypass))
            val values = arrayOf("proxy", "direct")
            val current = adapter.items[bindingAdapterPosition].second.outbound
            val checked = values.indexOf(current).coerceAtLeast(0)

            AlertDialog.Builder(this@RuleSetLibraryActivity)
                .setTitle(R.string.ruleset_library)
                .setSingleChoiceItems(options, checked) { _, which ->
                    updateState { it.copy(outbound = values[which]) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
