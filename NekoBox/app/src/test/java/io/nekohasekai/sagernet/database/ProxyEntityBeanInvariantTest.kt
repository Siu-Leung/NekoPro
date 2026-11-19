package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.masque.MASQUEBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProxyEntityBeanInvariantTest {
    @Test
    fun switchingAwayFromSnellClearsStaleSnellBean() {
        val entity = ProxyEntity().putBean(SnellBean())
        val socks = SOCKSBean().apply { serverAddress = "127.0.0.1"; serverPort = 1080 }
        entity.putBean(socks)
        assertNull(entity.snellBean)
        assertSame(socks, entity.socksBean)
    }

    @Test
    fun switchingAwayFromJuicityAndMasqueClearsStaleBeans() {
        val entity = ProxyEntity().putBean(JuicityBean())
        val masque = MASQUEBean().apply { serverAddress = "162.159.198.1"; serverPort = 443 }
        entity.putBean(masque)
        assertNull(entity.juicityBean)
        assertSame(masque, entity.masqueBean)

        val socks = SOCKSBean().apply { serverAddress = "127.0.0.1"; serverPort = 1080 }
        entity.putBean(socks)
        assertNull(entity.masqueBean)
        assertSame(socks, entity.socksBean)
    }
}
