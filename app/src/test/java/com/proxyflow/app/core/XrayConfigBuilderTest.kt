package com.proxyflow.app.core

import com.proxyflow.app.data.AppSettings
import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Builds the exact configurations the app sends to Xray-core. They are also written to
 * app/build/xray-configs/ so CI can run them with a real Xray binary against real upstream servers.
 */
class XrayConfigBuilderTest {
    private val settings = AppSettings()
    private val inbound = LocalInbound(10808, "local", "localpass")
    private val outDir = File(System.getProperty("user.dir"), "build/xray-configs").apply { mkdirs() }

    private fun write(name: String, p: ProxyProfile): JSONObject {
        val json = XrayConfigBuilder.build(p, settings, inbound)
        File(outDir, "$name.json").writeText(json)
        return JSONObject(json)
    }

    @Test fun httpProfile() {
        val c = write("client-http-auth", ProxyProfile(name = "h", protocol = Protocol.HTTP, host = "127.0.0.1", port = 18080,
            authEnabled = true, username = "huser", password = "hpass"))
        val out = c.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("http", out.getString("protocol"))
        assertEquals("huser", out.getJSONObject("settings").getString("user"))
        val rules = c.getJSONObject("routing").getJSONArray("rules").toString()
        assertTrue("UDP must be blocked for HTTP", rules.contains("\"network\":\"udp\""))
        assertTrue(c.getJSONObject("dns").getJSONArray("servers").getString(0).startsWith("https://"))
        write("client-http-wrongpass", ProxyProfile(name = "h", protocol = Protocol.HTTP, host = "127.0.0.1", port = 18080,
            authEnabled = true, username = "huser", password = "WRONG"))
        write("client-http-noauth", ProxyProfile(name = "h", protocol = Protocol.HTTP, host = "127.0.0.1", port = 18081))
    }

    @Test fun socksProfile() {
        val c = write("client-socks-auth", ProxyProfile(name = "s", protocol = Protocol.SOCKS5, host = "127.0.0.1", port = 11080,
            authEnabled = true, username = "suser", password = "spass"))
        assertEquals("socks", c.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertFalse(c.getJSONObject("routing").getJSONArray("rules").toString().contains("\"network\":\"udp\""))
        write("client-socks-noauth", ProxyProfile(name = "s", protocol = Protocol.SOCKS5, host = "127.0.0.1", port = 11081))
        val local = write("client-socks-localdns", ProxyProfile(name = "s", protocol = Protocol.SOCKS5, host = "127.0.0.1", port = 11081, dnsMode = DnsMode.LOCAL))
        assertEquals("1.1.1.1", local.getJSONObject("dns").getJSONArray("servers").getString(0))
    }

    @Test fun vlessProfiles() {
        val tcp = write("client-vless-tcp", LinkCodec.parse("vless://5783a3e7-e373-51cd-8642-c83782b807c5@127.0.0.1:14443?type=tcp&security=none#t"))
        val ss = tcp.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("raw", ss.getString("network"))
        write("client-vless-ws", LinkCodec.parse("vless://5783a3e7-e373-51cd-8642-c83782b807c5@127.0.0.1:14444?type=ws&path=%2Fws&security=none#w"))
        val reality = write("client-vless-reality-syntax", LinkCodec.parse(
            "vless://5783a3e7-e373-51cd-8642-c83782b807c5@203.0.113.1:443?type=tcp&flow=xtls-rprx-vision&security=reality&sni=www.example.com&fp=chrome&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179e30d4fc2#r"))
        val rs = reality.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw", rs.getString("publicKey"))
        write("client-vless-wstls-syntax", LinkCodec.parse(
            "vless://5783a3e7-e373-51cd-8642-c83782b807c5@example.com:443?type=ws&path=%2Fray&security=tls&sni=example.com&fp=chrome#wt"))
        write("client-vless-grpc-syntax", LinkCodec.parse(
            "vless://5783a3e7-e373-51cd-8642-c83782b807c5@example.com:443?type=grpc&serviceName=gun&security=tls&sni=example.com#g"))
        write("client-vless-xhttp-syntax", LinkCodec.parse(
            "vless://5783a3e7-e373-51cd-8642-c83782b807c5@example.com:443?type=xhttp&path=%2Fx&mode=auto&security=tls&sni=example.com#x"))
    }
}
