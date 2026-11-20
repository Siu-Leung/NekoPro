/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.toStringPretty
import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONArray
import org.json.JSONObject

fun buildSingBoxOutboundMieruBean(bean: MieruBean): SingBoxOptions.Outbound_MieruOptions {
    return SingBoxOptions.Outbound_MieruOptions().apply {
        type = "mieru"
        server = bean.serverAddress?.trim()?.removeSurrounding("[", "]")
        server_port = bean.serverPort
        transport = (bean.protocol?.trim()?.uppercase()).takeUnless { it.isNullOrEmpty() } ?: "TCP"
        udp = true
        username = bean.username ?: ""
        password = bean.password ?: ""
    }
}

fun MieruBean.buildMieruConfig(port: Int): String {
    val serverInfo = JSONArray().apply {
        put(JSONObject().apply {
            put("ipAddress", finalAddress)
            put("portBindings", JSONArray().apply {
                put(JSONObject().apply {
                    put("port", finalPort)
                    put("protocol", protocol)
                })
            })
        })
    }
    return JSONObject().apply {
        put("activeProfile", "default")
        put("socks5Port", port)
        // TODO: follow NekoBox logging level.
        put("loggingLevel", "INFO")
        put("profiles", JSONArray().apply {
            put(JSONObject().apply {
                put("profileName", "default")
                put("user", JSONObject().apply {
                    put("name", username)
                    put("password", password)
                })
                put("servers", serverInfo)
                put("mtu", mtu)
            })
        })
    }.toStringPretty()
}
