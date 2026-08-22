package io.nekohasekai.sagernet.fmt.ssh

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters

class SSHBean : AbstractBean() {

    @JvmField var username: String? = null
    @JvmField var authType: Int? = null
    @JvmField var password: String? = null
    @JvmField var privateKey: String? = null
    @JvmField var privateKeyPassphrase: String? = null
    @JvmField var publicKey: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (serverPort == null) serverPort = 22
        if (username == null) username = ""
        if (authType == null) authType = AUTH_TYPE_NONE
        if (password == null) password = ""
        if (privateKey == null) privateKey = ""
        if (privateKeyPassphrase == null) privateKeyPassphrase = ""
        if (publicKey == null) publicKey = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        super.serialize(output)
        output.writeString(username)
        output.writeInt(authType!!)
        output.writeString(password)
        output.writeString(privateKey)
        output.writeString(privateKeyPassphrase)
        output.writeString(publicKey)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        username = input.readString()
        authType = input.readInt()
        password = input.readString()
        privateKey = input.readString()
        privateKeyPassphrase = input.readString()
        publicKey = input.readString()
    }

    override fun clone(): SSHBean =
        KryoConverters.deserialize(SSHBean(), KryoConverters.serialize(this))

    companion object {
        const val AUTH_TYPE_NONE = 0
        const val AUTH_TYPE_PASSWORD = 1
        const val AUTH_TYPE_PRIVATE_KEY = 2

        @JvmField val CREATOR = object : CREATOR<SSHBean>() {
            override fun newInstance() = SSHBean()
            override fun newArray(size: Int) = arrayOfNulls<SSHBean>(size)
        }
    }
}
