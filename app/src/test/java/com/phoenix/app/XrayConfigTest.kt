package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class XrayConfigTest {
    private fun dump(name: String, json: String) {
        System.getProperty("phoenix.dump")?.let { File(it, "$name.json").writeText(json) }
    }

    private fun fails(link: String): String =
        try {
            XrayConfig.vpnConfig(link); "OK"
        } catch (e: UnsupportedLinkException) {
            e.message ?: ""
        }

    @Test
    fun vlessRealityVision() {
        val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&pbk=PUBKEY&sid=ab12&sni=www.microsoft.com&fp=firefox&flow=xtls-rprx-vision&spx=%2F#name"
        val json = XrayConfig.vpnConfig(link)
        dump("vless_reality", json)
        assertTrue(Json.looksLikeObject(json))
        assertTrue(json.contains("\"protocol\":\"vless\""))
        assertTrue(json.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(json.contains("\"security\":\"reality\""))
        assertTrue(json.contains("\"publicKey\":\"PUBKEY\""))
        assertTrue(json.contains("\"shortId\":\"ab12\""))
        assertTrue(json.contains("\"serverName\":\"www.microsoft.com\""))
        assertTrue(json.contains("\"fingerprint\":\"firefox\""))
        assertTrue(json.contains("\"spiderX\":\"/\""))
        assertTrue(json.contains("\"protocol\":\"tun\""))
        assertTrue(json.contains("\"tag\":\"proxy\""))
    }

    @Test
    fun vlessWsTlsKeepsHostAndPath() {
        val link = "vless://id1@1.2.3.4:8443?type=ws&security=tls&host=cdn.example.org&path=%2Fws%3Fed%3D2048&sni=cdn.example.org&alpn=h2,http/1.1#x"
        val json = XrayConfig.vpnConfig(link)
        dump("vless_ws", json)
        assertTrue(json.contains("\"path\":\"/ws?ed=2048\""))
        assertTrue(json.contains("\"headers\":{\"Host\":\"cdn.example.org\"}"))
        assertTrue(json.contains("\"alpn\":[\"h2\",\"http/1.1\"]"))
    }

    @Test
    fun sniFallsBackToDomainButNotToIp() {
        val dom = XrayConfig.vpnConfig("vless://id@host.example.com:443?security=tls#x")
        assertTrue(dom.contains("\"serverName\":\"host.example.com\""))
        val ip = XrayConfig.vpnConfig("vless://id@9.9.9.9:443?security=tls#x")
        assertFalse(ip.contains("serverName"))
    }

    @Test
    fun xhttpExtraIsEmbeddedAsObject() {
        val extra = "{\"xPaddingBytes\":\"100-1000\",\"noGRPCHeader\":true}"
        val enc = java.net.URLEncoder.encode(extra, "UTF-8")
        val json = XrayConfig.vpnConfig("vless://id@a.com:443?type=xhttp&security=tls&path=%2Fx&mode=auto&extra=$enc#x")
        dump("vless_xhttp", json)
        assertTrue(json.contains("\"extra\":$extra"))
        assertTrue(json.contains("\"mode\":\"auto\""))
        // испорченный extra не должен ломать весь конфиг
        val bad = XrayConfig.vpnConfig("vless://id@a.com:443?type=xhttp&security=tls&extra=%7Bbroken#x")
        assertFalse(bad.contains("extra"))
    }

    @Test
    fun grpcSettings() {
        val json = XrayConfig.vpnConfig("vless://id@a.com:443?type=grpc&security=tls&serviceName=svc&mode=multi#x")
        assertTrue(json.contains("\"serviceName\":\"svc\""))
        assertTrue(json.contains("\"multiMode\":true"))
    }

    @Test
    fun trojanDefaultsToTls() {
        val json = XrayConfig.vpnConfig("trojan://p%40ss+word@t.example.com:443#x")
        dump("trojan", json)
        assertTrue(json.contains("\"password\":\"p@ss+word\""))
        assertTrue(json.contains("\"security\":\"tls\""))
        assertTrue(json.contains("\"serverName\":\"t.example.com\""))
    }

    @Test
    fun vmessFromBase64Json() {
        val vm = "{\"v\":\"2\",\"ps\":\"n\",\"add\":\"v.example.com\",\"port\":\"443\",\"id\":\"uuid-1\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"v.example.com\",\"path\":\"/p\",\"tls\":\"tls\",\"sni\":\"\"}"
        val link = "vmess://" + Base64.getEncoder().encodeToString(vm.toByteArray())
        val json = XrayConfig.vpnConfig(link)
        dump("vmess", json)
        assertTrue(json.contains("\"protocol\":\"vmess\""))
        assertTrue(json.contains("\"address\":\"v.example.com\""))
        assertTrue(json.contains("\"port\":443"))
        assertTrue(json.contains("\"id\":\"uuid-1\""))
        assertTrue(json.contains("\"network\":\"ws\""))
        assertTrue(json.contains("\"security\":\"tls\""))
        assertTrue(json.contains("\"alterId\":0"))
    }

    @Test
    fun shadowsocksAllForms() {
        val creds = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:secret".toByteArray())
        val sip = XrayConfig.vpnConfig("ss://$creds@s.example.com:8388#x")
        dump("ss", sip)
        assertTrue(sip.contains("\"method\":\"aes-256-gcm\""))
        assertTrue(sip.contains("\"password\":\"secret\""))
        assertTrue(sip.contains("\"port\":8388"))

        val plain = XrayConfig.vpnConfig("ss://chacha20-ietf-poly1305:pw@1.1.1.1:443#x")
        assertTrue(plain.contains("\"method\":\"chacha20-ietf-poly1305\""))

        val legacy = Base64.getEncoder().encodeToString("aes-128-gcm:pw@2.2.2.2:1234".toByteArray())
        val old = XrayConfig.vpnConfig("ss://$legacy#x")
        assertTrue(old.contains("\"address\":\"2.2.2.2\""))
        assertTrue(old.contains("\"port\":1234"))
    }

    @Test
    fun unsupportedThingsGiveReadableErrors() {
        assertTrue(fails("vless://id@a.com:443?type=kcp#x").contains("kcp"))
        assertTrue(fails("vless://id@a.com:443?security=reality#x").contains("pbk"))
        assertTrue(fails("ss://aes-256-cfb:pw@a.com:1#x").contains("aes-256-cfb"))
        assertTrue(fails("ss://aes-256-gcm:pw@a.com:1?plugin=v2ray-plugin#x").contains("плагин"))
        assertTrue(fails("tuic://a@b.com:1#x").isNotBlank())
        assertEquals("OK", fails("trojan://pw@a.com:443#x"))
    }

    @Test
    fun probeConfigHasNoInbounds() {
        val json = XrayConfig.probeConfig("trojan://pw@a.com:443#x")
        dump("probe", json)
        assertFalse(json.contains("inbounds"))
        assertTrue(json.contains("\"tag\":\"proxy\""))
    }

    @Test
    fun jsonWriterEscapesAndSkipsNulls() {
        val s = Json.write(mapOf("a" to "q\"\\\n", "b" to null, "c" to listOf(1, true)))
        assertEquals("{\"a\":\"q\\\"\\\\\\n\",\"c\":[1,true]}", s)
        assertTrue(Json.looksLikeObject("{\"a\":{\"b\":[1,2]}}"))
        assertFalse(Json.looksLikeObject("{\"a\":"))
        assertFalse(Json.looksLikeObject("{}{}"))
    }
}

class XudpKeyTest {
    @Test
    fun keyIs32BytesBase64UrlWithoutPadding() {
        val k = XudpKey.make()
        assertTrue(XudpKey.isValid(k))
        assertFalse(k.contains('='))
        assertEquals(43, k.length) // 32 байта -> 43 символа base64 без «=»
        assertFalse(XudpKey.isValid("2f1c2c0e-4f7c-4d36-9d77-7d5a3f6c6a11")) // прежний UUID ядро не принимало
        assertFalse(XudpKey.isValid(""))
    }
}
