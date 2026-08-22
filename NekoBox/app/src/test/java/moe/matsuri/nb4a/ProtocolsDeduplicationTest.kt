package moe.matsuri.nb4a

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProtocolsDeduplicationTest {
    private fun socks(password: String, name: String) = SOCKSBean().apply {
        serverAddress = "shared.example"
        serverPort = 443
        username = "user"
        this.password = password
        this.name = name
    }

    @Test
    fun sameEndpointWithDifferentCredentialsIsNotDuplicate() {
        assertNotEquals(
            Protocols.Deduplication(socks("one", "first"), "socks"),
            Protocols.Deduplication(socks("two", "second"), "socks")
        )
    }

    @Test
    fun displayNameIsExcludedFromConnectionIdentity() {
        assertEquals(
            Protocols.Deduplication(socks("same", "first"), "socks"),
            Protocols.Deduplication(socks("same", "second"), "socks")
        )
    }

    @Test
    fun transportAndTlsParametersArePartOfIdentity() {
        val plain = HttpBean().apply {
            serverAddress = "shared.example"; serverPort = 443; username = "u"; password = "p"
            security = ""
        }
        val tls = HttpBean().apply {
            serverAddress = "shared.example"; serverPort = 443; username = "u"; password = "p"
            security = "tls"
        }
        assertNotEquals(
            Protocols.Deduplication(plain, "http"),
            Protocols.Deduplication(tls, "http")
        )
    }
}
