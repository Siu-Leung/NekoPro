package io.nekohasekai.sagernet.database

import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues

@Entity(tableName = "proxy_groups")
data class ProxyGroup(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var ungrouped: Boolean = false,
    var name: String? = null,
    var type: Int = GroupType.BASIC,
    var subscription: SubscriptionBean? = null,
    var order: Int = GroupOrder.ORIGIN,
    var isSelector: Boolean = false,
    var frontProxy: Long = -1L,
    var landingProxy: Long = -1L,
    // 本组所有内置节点共用的手填入口 DNS。旧订阅将手填值存于 SubscriptionBean；读取时兼容。
    var outboundDns: String? = null,
) : Serializable() {

    @Transient
    var export = false

    /** 返回组级手填 DNS；未编辑的旧订阅仍可使用原来的手填值。 */
    fun manualNodeDns(): String? = outboundDns?.trim()?.takeIf { it.isNotEmpty() }
        ?: subscription?.nodeDns?.trim()?.takeIf { type == GroupType.SUBSCRIPTION && it.isNotEmpty() }

    /** 保存组级 DNS，清掉旧字段，避免用户清空输入后又意外恢复旧值。 */
    fun saveManualNodeDns(value: String) {
        outboundDns = value.trim().takeIf { it.isNotEmpty() }
        subscription?.nodeDns = ""
    }

    /** 返回拨号时使用的解析器列表（每行一个端点）；手填优先，自动采纳仅对订阅组有效。 */
    fun effectiveNodeDnsList(): List<String> = (manualNodeDns()
        ?: subscription?.takeIf { type == GroupType.SUBSCRIPTION }?.effectiveNodeDns())
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        .orEmpty()

    /** 兼容旧调用方的单值形式（主端点）。 */
    fun effectiveNodeDns(): String? = effectiveNodeDnsList().firstOrNull()

    /** 订阅刷新是否必须携带可用的节点 DNS。手填已经满足要求时，不强制来源额外下发。 */
    fun requiresNodeDnsAdoption(): Boolean = type == GroupType.SUBSCRIPTION &&
        subscription?.autoAdoptNodeDns == true && manualNodeDns() == null

    override fun initializeDefaultValues() {
        subscription?.applyDefaultValues()
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        if (export) {
            // 分享链接会离开本机；不要把可能带凭据的 DNS URL 放进去。
            val subscriptionDescriptor = requireNotNull(subscription) {
                "Cannot export non-subscription ProxyGroup as subscription share link"
            }
            output.writeInt(0)
            output.writeString(name)
            output.writeInt(type)
            subscriptionDescriptor.serializeForShare(output)
        } else {
            output.writeInt(2)
            output.writeLong(id)
            output.writeLong(userOrder)
            output.writeBoolean(ungrouped)
            output.writeString(name)
            output.writeInt(type)

            if (type == GroupType.SUBSCRIPTION) {
                val sub = subscription
                output.writeBoolean(sub != null)
                sub?.serializeToBuffer(output)
            }
            output.writeInt(order)
            output.writeBoolean(isSelector)
            output.writeLong(frontProxy)
            output.writeLong(landingProxy)
            output.writeString(outboundDns)
        }
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        outboundDns = null
        if (export) {
            val version = input.readInt()
            require(version == 0) { "Unsupported exported ProxyGroup version: $version" }

            name = input.readString()
            type = input.readInt()
            val subscription = SubscriptionBean()
            this.subscription = subscription

            subscription.deserializeFromShare(input)
        } else {
            val version = input.readInt()
            require(version in 0..2) { "Unsupported ProxyGroup serialization version: $version" }

            id = input.readLong()
            userOrder = input.readLong()
            ungrouped = input.readBoolean()
            name = input.readString()
            type = input.readInt()

            if (type == GroupType.SUBSCRIPTION) {
                val hasSubscription = if (version >= 1) input.readBoolean() else true
                if (hasSubscription) {
                    val subscription = SubscriptionBean()
                    this.subscription = subscription

                    subscription.deserializeFromBuffer(input)
                } else {
                    this.subscription = null
                }
            }
            order = input.readInt()
            if (version >= 1) {
                isSelector = input.readBoolean()
                frontProxy = input.readLong()
                landingProxy = input.readLong()
            }
            // 按版本读取，而非用当前缓冲区剩余长度判断，避免跨块时读错后续记录。
            if (version >= 2) outboundDns = input.readString()
        }
    }

    fun displayName(): String {
        return name.takeIf { !it.isNullOrBlank() } ?: app.getString(R.string.group_default)
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM proxy_groups ORDER BY userOrder")
        fun allGroups(): List<ProxyGroup>

        @Query("SELECT * FROM proxy_groups WHERE type = ${GroupType.SUBSCRIPTION}")
        suspend fun subscriptions(): List<ProxyGroup>

        @Query("SELECT MAX(userOrder) + 1 FROM proxy_groups")
        fun nextOrder(): Long?

        @Query("SELECT * FROM proxy_groups WHERE id = :groupId")
        fun getById(groupId: Long): ProxyGroup?

        @Query("DELETE FROM proxy_groups WHERE id = :groupId")
        fun deleteById(groupId: Long): Int

        @Delete
        fun deleteGroup(group: ProxyGroup)

        @Delete
        fun deleteGroup(groupList: List<ProxyGroup>)

        @Insert
        fun createGroup(group: ProxyGroup): Long

        @Update
        fun updateGroup(group: ProxyGroup)

        @Query("DELETE FROM proxy_groups")
        fun reset()

        @Insert
        fun insert(groupList: List<ProxyGroup>)

    }

    companion object {
        @JvmField
        val CREATOR = object : Serializable.CREATOR<ProxyGroup>() {

            override fun newInstance(): ProxyGroup {
                return ProxyGroup()
            }

            override fun newArray(size: Int): Array<ProxyGroup?> {
                return arrayOfNulls(size)
            }
        }
    }

}