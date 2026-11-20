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
        if (input.limit() - input.position() > 0) {
            autoUpdate = input.readBoolean()
            autoUpdateDelay = input.readInt()
            lastUpdated = input.readInt()
        }
        if (version >= 1 && input.limit() - input.position() > 0) {
            subscriptionUserinfo = input.readString()
        }
        if (version >= 2 && input.limit() - input.position() > 0) {
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

    companion object {
        @JvmField val CREATOR = object : CREATOR<SubscriptionBean>() {
            override fun newInstance() = SubscriptionBean()
            override fun newArray(size: Int) = arrayOfNulls<SubscriptionBean>(size)
        }
    }
}
