package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

class SubscriptionBean : Serializable() {

    @JvmField var type: Int? = null
    @JvmField var link: String? = null
    @JvmField var token: String? = null
    @JvmField var forceResolve: Boolean? = null
    @JvmField var deduplication: Boolean? = null
    @JvmField var updateWhenConnectedOnly: Boolean? = null
    @JvmField var customUserAgent: String? = null
    @JvmField var autoUpdate: Boolean? = null
    @JvmField var autoUpdateDelay: Int? = null
    @JvmField var lastUpdated: Int? = null

    // SIP008
    @JvmField var bytesUsed: Long? = null
    @JvmField var bytesRemaining: Long? = null

    // Open Online Config
    @JvmField var username: String? = null
    @JvmField var expiryDate: Int? = null
    @JvmField var protocols: MutableList<String>? = null

    @JvmField var subscriptionUserinfo: String? = null
    @JvmField var nodeDns: String? = null
    @JvmField var autoAdoptNodeDns: Boolean? = null
    @JvmField var adoptedNodeDns: String? = null

    fun effectiveNodeDns(): String? {
        val manual = nodeDns?.trim()
        if (!manual.isNullOrEmpty()) return manual
        if (autoAdoptNodeDns == true) {
            return adoptedNodeDns?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(2)
        output.writeInt(type ?: 0)
        output.writeString(link)
        output.writeBoolean(forceResolve ?: false)
        output.writeBoolean(deduplication ?: false)
        output.writeBoolean(updateWhenConnectedOnly ?: false)
        output.writeString(customUserAgent)
        output.writeBoolean(autoUpdate ?: false)
        output.writeInt(autoUpdateDelay ?: 1440)
        output.writeInt(lastUpdated ?: 0)
        output.writeString(subscriptionUserinfo)
        output.writeString(nodeDns)
        output.writeBoolean(autoAdoptNodeDns ?: false)
        output.writeString(adoptedNodeDns)
    }

    fun serializeForShare(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeInt(type ?: 0)
        output.writeString(link)
        output.writeBoolean(forceResolve ?: false)
        output.writeBoolean(deduplication ?: false)
        output.writeBoolean(updateWhenConnectedOnly ?: false)
        output.writeString(customUserAgent)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()
        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
        // Gate optional fields strictly by version so embedded deserialization inside
        // ProxyGroup never over-reads subsequent ProxyGroup fields across record boundaries.
        if (version >= 1) {
            autoUpdate = input.readBoolean()
            autoUpdateDelay = input.readInt()
            lastUpdated = input.readInt()
            subscriptionUserinfo = input.readString()
        }
        if (version >= 2) {
            nodeDns = input.readString()
            autoAdoptNodeDns = input.readBoolean()
            adoptedNodeDns = input.readString()
        }
    }

    fun deserializeFromShare(input: ByteBufferInput) {
        val version = input.readInt()
        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
    }

    override fun initializeDefaultValues() {
        if (type == null) type = 0
        if (link == null) link = ""
        if (token == null) token = ""
        if (forceResolve == null) forceResolve = false
        if (deduplication == null) deduplication = false
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false
        if (customUserAgent == null) customUserAgent = ""
        if (autoUpdate == null) autoUpdate = false
        if (autoUpdateDelay == null) autoUpdateDelay = 1440
        if (lastUpdated == null) lastUpdated = 0
        if (bytesUsed == null) bytesUsed = 0L
        if (bytesRemaining == null) bytesRemaining = 0L
        if (username == null) username = ""
        if (expiryDate == null) expiryDate = 0
        if (protocols == null) protocols = mutableListOf()
        if (nodeDns == null) nodeDns = ""
        if (autoAdoptNodeDns == null) autoAdoptNodeDns = false
        if (adoptedNodeDns == null) adoptedNodeDns = ""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SubscriptionBean) return false
        return type == other.type &&
            link == other.link &&
            token == other.token &&
            forceResolve == other.forceResolve &&
            deduplication == other.deduplication &&
            updateWhenConnectedOnly == other.updateWhenConnectedOnly &&
            customUserAgent == other.customUserAgent &&
            autoUpdate == other.autoUpdate &&
            autoUpdateDelay == other.autoUpdateDelay &&
            lastUpdated == other.lastUpdated &&
            bytesUsed == other.bytesUsed &&
            bytesRemaining == other.bytesRemaining &&
            username == other.username &&
            expiryDate == other.expiryDate &&
            protocols == other.protocols &&
            nodeDns == other.nodeDns &&
            autoAdoptNodeDns == other.autoAdoptNodeDns &&
            adoptedNodeDns == other.adoptedNodeDns
    }

    override fun hashCode(): Int {
        var result = type?.hashCode() ?: 0
        result = 31 * result + (link?.hashCode() ?: 0)
        result = 31 * result + (token?.hashCode() ?: 0)
        result = 31 * result + (forceResolve?.hashCode() ?: 0)
        result = 31 * result + (deduplication?.hashCode() ?: 0)
        result = 31 * result + (updateWhenConnectedOnly?.hashCode() ?: 0)
        result = 31 * result + (customUserAgent?.hashCode() ?: 0)
        result = 31 * result + (autoUpdate?.hashCode() ?: 0)
        result = 31 * result + (autoUpdateDelay?.hashCode() ?: 0)
        result = 31 * result + (lastUpdated?.hashCode() ?: 0)
        result = 31 * result + (bytesUsed?.hashCode() ?: 0)
        result = 31 * result + (bytesRemaining?.hashCode() ?: 0)
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (expiryDate?.hashCode() ?: 0)
        result = 31 * result + (protocols?.hashCode() ?: 0)
        result = 31 * result + (nodeDns?.hashCode() ?: 0)
        result = 31 * result + (autoAdoptNodeDns?.hashCode() ?: 0)
        result = 31 * result + (adoptedNodeDns?.hashCode() ?: 0)
        return result
    }

    companion object {
        @JvmField val CREATOR = object : CREATOR<SubscriptionBean>() {
            override fun newInstance() = SubscriptionBean()
            override fun newArray(size: Int) = arrayOfNulls<SubscriptionBean>(size)
        }
    }
}
