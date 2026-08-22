package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.fmt.AbstractBean

abstract class InternalBean : AbstractBean() {
    override fun displayAddress(): String = ""
    override fun canICMPing(): Boolean = false
    override fun canTCPing(): Boolean = false
    override fun canMapping(): Boolean = false
}
