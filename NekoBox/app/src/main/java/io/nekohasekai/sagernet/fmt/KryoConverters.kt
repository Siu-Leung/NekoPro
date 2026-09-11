package io.nekohasekai.sagernet.fmt

import androidx.room.TypeConverter
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.utils.JavaUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object KryoConverters {

    private val NULL = byteArrayOf()

    @TypeConverter
    @JvmStatic
    fun serialize(bean: Serializable?): ByteArray {
        if (bean == null) return NULL
        val out = ByteArrayOutputStream()
        val buffer = ByteBufferOutput(out)
        bean.serializeToBuffer(buffer)
        buffer.flush()
        buffer.close()
        return out.toByteArray()
    }

    @JvmStatic
    fun <T : Serializable> deserialize(bean: T, bytes: ByteArray?): T {
        if (bytes == null) return bean
        val input = ByteArrayInputStream(bytes)
        val buffer = ByteBufferInput(input)
        try {
            bean.deserializeFromBuffer(buffer)
        } catch (e: KryoException) {
            Logs.w(e)
        }
        bean.initializeDefaultValues()
        return bean
    }

    @TypeConverter @JvmStatic fun snellDeserialize(bytes: ByteArray?): SnellBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(SnellBean(), bytes)

    @TypeConverter @JvmStatic fun socksDeserialize(bytes: ByteArray?): SOCKSBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(SOCKSBean(), bytes)

    @TypeConverter @JvmStatic fun httpDeserialize(bytes: ByteArray?): HttpBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(HttpBean(), bytes)

    @TypeConverter @JvmStatic fun shadowsocksDeserialize(bytes: ByteArray?): ShadowsocksBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(ShadowsocksBean(), bytes)

    @TypeConverter @JvmStatic fun configDeserialize(bytes: ByteArray?): ConfigBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(ConfigBean(), bytes)

    @TypeConverter @JvmStatic fun vmessDeserialize(bytes: ByteArray?): VMessBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(VMessBean(), bytes)

    @TypeConverter @JvmStatic fun trojanDeserialize(bytes: ByteArray?): TrojanBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(TrojanBean(), bytes)

    @TypeConverter @JvmStatic fun trojanGoDeserialize(bytes: ByteArray?): TrojanGoBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(TrojanGoBean(), bytes)

    @TypeConverter @JvmStatic fun mieruDeserialize(bytes: ByteArray?): MieruBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(MieruBean(), bytes)

    @TypeConverter @JvmStatic fun naiveDeserialize(bytes: ByteArray?): NaiveBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(NaiveBean(), bytes)

    @TypeConverter @JvmStatic fun hysteriaDeserialize(bytes: ByteArray?): HysteriaBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(HysteriaBean(), bytes)

    @TypeConverter @JvmStatic fun sshDeserialize(bytes: ByteArray?): SSHBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(SSHBean(), bytes)

    @TypeConverter @JvmStatic fun wireguardDeserialize(bytes: ByteArray?): WireGuardBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(WireGuardBean(), bytes)

    @TypeConverter @JvmStatic fun tuicDeserialize(bytes: ByteArray?): TuicBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(TuicBean(), bytes)

    @TypeConverter @JvmStatic fun shadowTLSDeserialize(bytes: ByteArray?): ShadowTLSBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(ShadowTLSBean(), bytes)

    @TypeConverter @JvmStatic fun anyTLSDeserialize(bytes: ByteArray?): AnyTLSBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(AnyTLSBean(), bytes)

    @TypeConverter @JvmStatic fun chainDeserialize(bytes: ByteArray?): ChainBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(ChainBean(), bytes)

    @TypeConverter @JvmStatic fun nekoDeserialize(bytes: ByteArray?): NekoBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(NekoBean(), bytes)

    @TypeConverter @JvmStatic fun subscriptionDeserialize(bytes: ByteArray?): SubscriptionBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(SubscriptionBean(), bytes)

    @TypeConverter @JvmStatic fun juicityDeserialize(bytes: ByteArray?): JuicityBean? =
        if (JavaUtil.isEmpty(bytes)) null else deserialize(JuicityBean(), bytes)
}
