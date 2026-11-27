package com.arin.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Network security assessment from the phone (Arin's own code, no AI): this network's settings (Wi-Fi security, gateway,
 * DNS, private DNS), devices answering on the local network, and open services on a chosen host (ordinary TCP
 * connections, the same as an app opening a connection — no attacks, no password guessing, no packet injection).
 * Limits are stated honestly: without root (PRoot isn't root) Android allows no raw packets or Wi-Fi monitor mode.
 */
object NetAudit {
    /** Common services: port → name and why an open one matters. */
    val PORTS: Map<Int, Pair<String, String>> = linkedMapOf(
        21 to ("FTP" to "Passwords travel unencrypted. Use SFTP."), 22 to ("SSH" to "Make sure only keys (no passwords) are allowed."),
        23 to ("Telnet" to "Unencrypted remote login — turn it off."), 25 to ("SMTP" to ""), 53 to ("DNS" to "An open DNS resolver can be abused; answer only your own network."),
        80 to ("HTTP" to "Admin pages over plain HTTP send passwords unencrypted."), 110 to ("POP3" to ""), 139 to ("NetBIOS" to "Windows file sharing — keep it off untrusted networks."),
        143 to ("IMAP" to ""), 443 to ("HTTPS" to ""), 445 to ("SMB" to "Windows file sharing — never expose to the internet."), 548 to ("AFP" to ""),
        554 to ("RTSP (cameras)" to "Camera streams: make sure a password is required."), 631 to ("IPP printing" to ""), 993 to ("IMAPS" to ""),
        1883 to ("MQTT" to "IoT messages; require a login and TLS."), 1900 to ("UPnP" to "Lets devices open router ports by themselves — turn UPnP off if unused."),
        3306 to ("MySQL" to "Databases should not be reachable from other devices."), 3389 to ("Remote Desktop" to "Exposed remote desktop is a top attack target."),
        5000 to ("UPnP/dev server" to ""), 5432 to ("PostgreSQL" to "Databases should not be reachable from other devices."), 5555 to ("ADB over network" to "Anyone on the network can control this Android device — turn it off."),
        5900 to ("VNC" to "Remote screen access — require a strong password."), 6379 to ("Redis" to "Often has no password — never expose."),
        7547 to ("TR-069 (ISP management)" to "ISP remote-management port; should not answer on the local side."), 8000 to ("HTTP alt" to ""),
        8080 to ("HTTP proxy/admin" to "Often an admin page; check it needs a password."), 8443 to ("HTTPS alt" to ""), 8888 to ("HTTP alt" to ""),
        9100 to ("Printer raw" to ""), 27017 to ("MongoDB" to "Often has no password — never expose."),
    )

    data class Host(val ip: String, val open: List<Int>)

    /** This phone's current network: Wi-Fi security, gateway, DNS, limits. */
    fun networkInfo(c: Context): String {
        val sb = StringBuilder("# This network\n")
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        sb.appendLine("- Connection: " + when { caps == null -> "none"; wifi -> "Wi-Fi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"; else -> "other" })
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) sb.appendLine("- A VPN is active")
        lp?.let { l ->
            sb.appendLine("- Your IP: " + l.linkAddresses.joinToString { it.address.hostAddress.orEmpty() })
            sb.appendLine("- Gateway (router): " + l.routes.mapNotNull { it.gateway?.hostAddress }.filter { it != "0.0.0.0" && it != "::" }.distinct().joinToString().ifBlank { "unknown" })
            sb.appendLine("- DNS servers: " + l.dnsServers.joinToString { it.hostAddress.orEmpty() })
            if (android.os.Build.VERSION.SDK_INT >= 28) sb.appendLine("- Private DNS (encrypted DNS): " + if (l.isPrivateDnsActive) "ON ✅ ${l.privateDnsServerName ?: "(automatic)"}" else "off — consider turning it on in Android settings")
        }
        if (wifi) {
            @Suppress("DEPRECATION")
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val ci = wm.connectionInfo
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val t = ci.currentSecurityType
                val name = mapOf(0 to "OPEN (no password) ⚠️", 1 to "WEP ⚠️ (broken — change it)", 2 to "WPA/WPA2-Personal", 3 to "WPA-Enterprise", 4 to "WPA3-Personal ✅", 5 to "WPA3-Enterprise ✅", 6 to "Enhanced Open (OWE)")[t] ?: "type $t"
                sb.appendLine("- Wi-Fi security: $name")
            } else sb.appendLine("- Wi-Fi security: Android ${android.os.Build.VERSION.RELEASE} doesn't tell apps this (needs Android 12+)")
            @Suppress("DEPRECATION") sb.appendLine("- Wi-Fi band: ${if (ci.frequency > 4900) "5/6 GHz" else "2.4 GHz"} · link ${ci.linkSpeed} Mbps")
        }
        sb.appendLine("\n_Limits on a phone: no root (Arin's Linux is PRoot, which is not root), so no raw packets, ARP listing or Wi-Fi monitor mode — device discovery uses ordinary connections._")
        return sb.toString()
    }

    /** The local network's address range (/24 of this phone's IPv4). */
    fun localRange(c: Context): String? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress } ?: return null
        return ip.hostAddress!!.substringBeforeLast('.')
    }

    fun gateway(c: Context): String? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return lp.routes.mapNotNull { it.gateway }.firstOrNull { it is java.net.Inet4Address && it.hostAddress != "0.0.0.0" }?.hostAddress
    }

    private fun open(ip: String, port: Int, timeout: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout); true }
    }.getOrDefault(false)

    /** Devices on this phone's local network (x.x.x.1–254) that answer on common ports. */
    suspend fun discover(c: Context, onStep: (String) -> Unit): List<Host> = withContext(Dispatchers.IO) {
        val base = localRange(c) ?: return@withContext emptyList()
        val probe = listOf(80, 443, 22, 53, 8080, 445, 139, 554, 5555, 62078, 7000, 9100)
        val gate = Semaphore(48)
        var done = 0
        coroutineScope {
            (1..254).map { i ->
                async {
                    gate.withPermit {
                        val ip = "$base.$i"
                        val openPorts = probe.filter { open(ip, it, 350) }
                        val alive = openPorts.isNotEmpty() || runCatching { InetAddress.getByName(ip).isReachable(300) }.getOrDefault(false)
                        synchronized(this@NetAudit) { done++; if (done % 25 == 0) onStep("Checked $done of 254 addresses") }
                        if (alive) Host(ip, openPorts) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Open services on [host] (common ports, or [ports]), with a first line of what answers. */
    suspend fun scanHost(host: String, ports: Collection<Int> = PORTS.keys, onStep: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrElse { return@withContext "Couldn't find $host: ${it.message}" }
        onStep("Checking ${ports.size} common services on $host")
        val gate = Semaphore(24)
        val openPorts = coroutineScope { ports.map { p -> async { gate.withPermit { if (open(ip!!, p, 900)) p else null } } }.awaitAll().filterNotNull().sorted() }
        val sb = StringBuilder("# Services on $host ($ip)\n_Ordinary TCP connections to ${ports.size} common ports. ${java.util.Date()}_\n\n")
        if (openPorts.isEmpty()) sb.appendLine("- No common service answered (closed or filtered).")
        val risks = mutableListOf<String>()
        openPorts.forEach { p ->
            val (name, why) = PORTS[p] ?: ("port $p" to "")
            val banner = runCatching {
                Socket().use { s -> s.connect(InetSocketAddress(ip, p), 1200); s.soTimeout = 1200
                    if (p in setOf(80, 8080, 8000, 8888, 5000, 7547)) { s.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray()) }
                    s.getInputStream().bufferedReader().readLine()?.take(100) }
            }.getOrNull()
            sb.appendLine("- **$p $name** open" + (banner?.takeIf { it.isNotBlank() }?.let { " — `$it`" } ?: ""))
            if (why.isNotBlank()) risks += "$p $name: $why"
        }
        if (443 in openPorts || 8443 in openPorts) sb.appendLine("\n## TLS on ${if (443 in openPorts) 443 else 8443}\n" + WebAudit.tlsOnly(host, if (443 in openPorts) 443 else 8443))
        sb.append(VulnIntel.section(sb.toString(), onStep))
        sb.appendLine("\n## What to look at")
        if (risks.isEmpty()) sb.appendLine("- Nothing risky among the common services.") else risks.forEach { sb.appendLine("- $it") }
        sb.appendLine("\n---\n_Next step: close or protect anything you don't need (router settings → services/UPnP/remote management)._")
        sb.toString()
    }

    /** Router check: its admin page, risky services and remote management. */
    suspend fun routerCheck(c: Context, onStep: (String) -> Unit): String {
        val gw = gateway(c) ?: return "No router found (are you on Wi-Fi?)."
        val sb = StringBuilder("# Router check — $gw\n")
        sb.append(scanHost(gw, listOf(21, 22, 23, 53, 80, 443, 1900, 5000, 7547, 8080, 8443, 49152, 49000), onStep).substringAfter('\n'))
        if (open(gw, 80, 900) && !open(gw, 443, 900)) sb.appendLine("\n- ⚠️ The router's admin page is only on plain HTTP: its password travels unencrypted on your Wi-Fi.")
        sb.appendLine("\n**Router settings to check by hand:** strong admin password (not the default), WPA3 or WPA2-AES (no WEP/TKIP, no WPS), remote management off, UPnP off if unused, firmware up to date, guest network for smart devices.")
        return sb.toString()
    }
}
