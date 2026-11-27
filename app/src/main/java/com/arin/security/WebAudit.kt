package com.arin.security

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Passive web/API security check (Arin's own code, no AI): a handful of normal requests — security headers, cookies,
 * CORS, HTTPS redirect, TLS certificate and protocol, common API-description files. It never attacks, guesses
 * passwords or sends harmful input. For sites, APIs and labs the owner runs or is allowed to test.
 */
object WebAudit {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private data class F(val sev: String, val title: String, val detail: String)

    fun analyse(target0: String, onStep: (String) -> Unit = {}): String {
        val target = target0.trim().let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')
        val url = runCatching { java.net.URL(target) }.getOrElse { return "That isn't a valid web address." }
        val host = url.host
        val f = mutableListOf<F>()
        val sb = StringBuilder("# Web security report — $host\n_Passive checks only (normal requests). ${java.util.Date()}_\n")

        onStep("Fetching the page")
        val res = runCatching { client.newCall(Request.Builder().url(target).header("User-Agent", "Arin-SecurityCheck/1.0").build()).execute() }
            .getOrElse { return sb.append("\n⚠️ Couldn't reach $target: ${it.message}").toString() }
        val h = res.headers
        res.close()
        sb.appendLine("\n## Response\n- Status: ${res.code}" + (h["Location"]?.let { " → $it" } ?: "") + (h["Server"]?.let { "\n- Server: $it" } ?: ""))
        if (h["Server"]?.any { it.isDigit() } == true) f += F("LOW", "Server version shown", "The Server header reveals software versions (${h["Server"]}). Hide versions.")
        h["X-Powered-By"]?.let { f += F("LOW", "Technology disclosed", "X-Powered-By: $it — remove it.") }

        onStep("Checking security headers")
        sb.appendLine("\n## Security headers")
        val wanted = listOf(
            Triple("Strict-Transport-Security", "HIGH", "Forces HTTPS so connections can't be downgraded."),
            Triple("Content-Security-Policy", "MEDIUM", "Limits which scripts can run (protects against XSS)."),
            Triple("X-Content-Type-Options", "LOW", "Set to nosniff."),
            Triple("X-Frame-Options", "MEDIUM", "Stops other sites framing this page (clickjacking) — or use CSP frame-ancestors."),
            Triple("Referrer-Policy", "LOW", "Limits what URLs leak to other sites."),
            Triple("Permissions-Policy", "LOW", "Turns off browser features the site doesn't use."),
        )
        wanted.forEach { (name, sev, why) ->
            val v = h[name]
            sb.appendLine("- " + (if (v != null) "✅ $name: ${v.take(120)}" else "❌ $name missing"))
            if (v == null && !(name == "X-Frame-Options" && h["Content-Security-Policy"]?.contains("frame-ancestors") == true)) f += F(sev, "$name missing", why)
        }
        h["Strict-Transport-Security"]?.let { v -> Regex("max-age=(\\d+)").find(v)?.groupValues?.get(1)?.toLongOrNull()?.let { if (it < 15_552_000) f += F("LOW", "Short HSTS max-age ($it s)", "Use at least 6 months (15552000).") } }
        h["Content-Security-Policy"]?.let { if (it.contains("'unsafe-inline'") || it.contains("'unsafe-eval'")) f += F("MEDIUM", "Weak Content-Security-Policy", "It allows unsafe-inline/unsafe-eval, which weakens XSS protection.") }

        sb.appendLine("\n## Cookies")
        val cookies = h.values("Set-Cookie")
        if (cookies.isEmpty()) sb.appendLine("- none set on this page")
        cookies.forEach { c ->
            val name = c.substringBefore('=')
            val secure = c.contains("Secure", true); val httpOnly = c.contains("HttpOnly", true); val same = Regex("(?i)SameSite=(\\w+)").find(c)?.groupValues?.get(1)
            sb.appendLine("- `$name` · Secure ${if (secure) "✅" else "❌"} · HttpOnly ${if (httpOnly) "✅" else "❌"} · SameSite ${same ?: "❌ not set"}")
            if (!secure) f += F("MEDIUM", "Cookie $name without Secure", "It can be sent over plain HTTP.")
            if (!httpOnly && Regex("(?i)sess|auth|token|sid").containsMatchIn(name)) f += F("MEDIUM", "Session cookie $name readable by scripts", "Add HttpOnly.")
            if (same.equals("None", true) && !secure) f += F("MEDIUM", "Cookie $name: SameSite=None without Secure", "Browsers reject or misuse this.")
        }

        onStep("Checking CORS")
        sb.appendLine("\n## CORS")
        runCatching {
            client.newCall(Request.Builder().url(target).header("Origin", "https://arin-cors-check.example").build()).execute().use { r ->
                val acao = r.header("Access-Control-Allow-Origin"); val creds = r.header("Access-Control-Allow-Credentials")
                sb.appendLine("- Allow-Origin for a foreign site: ${acao ?: "not sent"}" + (creds?.let { " · Allow-Credentials: $it" } ?: ""))
                when {
                    acao == "https://arin-cors-check.example" && creds == "true" -> f += F("HIGH", "CORS trusts any website, with cookies", "The server echoes any Origin and allows credentials: another site could read users' data. Allow only your own origins.")
                    acao == "https://arin-cors-check.example" -> f += F("MEDIUM", "CORS echoes any origin", "Any website can read these responses. Allow only your own origins.")
                    acao == "*" && creds == "true" -> f += F("MEDIUM", "CORS * with credentials", "Invalid and risky combination.")
                }
            }
        }.onFailure { sb.appendLine("- couldn't check: ${it.message}") }

        if (url.protocol == "https") {
            onStep("Checking the HTTP → HTTPS redirect")
            runCatching {
                client.newCall(Request.Builder().url("http://$host/").build()).execute().use { r ->
                    val loc = r.header("Location").orEmpty()
                    sb.appendLine("\n## Plain HTTP\n- http://$host/ → ${r.code} ${loc.ifBlank { "(no redirect)" }}")
                    if (!(r.code in 300..399 && loc.startsWith("https"))) f += F("MEDIUM", "Plain HTTP doesn't redirect to HTTPS", "Redirect all http:// requests to https://.")
                }
            }
            onStep("Checking the TLS certificate")
            sb.appendLine("\n## TLS")
            sb.append(tls(host, if (url.port > 0) url.port else 443, f))
        } else f += F("HIGH", "Site uses plain HTTP", "Everything travels unencrypted. Serve it over HTTPS.")

        onStep("Looking for API descriptions")
        val specs = listOf("/openapi.json", "/swagger.json", "/v3/api-docs", "/api-docs", "/swagger-ui/", "/.well-known/security.txt")
        sb.appendLine("\n## API description / security.txt")
        specs.forEach { p ->
            runCatching {
                client.newCall(Request.Builder().url("${url.protocol}://$host$p").build()).execute().use { r ->
                    if (r.code == 200) {
                        sb.appendLine("- `$p` found")
                        if (p != "/.well-known/security.txt") f += F("LOW", "API description public ($p)", "Fine for public APIs; for private ones it maps every endpoint for attackers — put it behind sign-in.")
                    }
                }
            }
        }
        // Known vulnerabilities for software versions the site reveals.
        VulnIntel.section(listOfNotNull(h["Server"], h["X-Powered-By"]).joinToString(" "), onStep).takeIf { it.isNotBlank() }?.let {
            sb.append(it)
            if (it.contains("EXPLOITED IN THE WILD")) f += F("HIGH", "Software with actively exploited vulnerabilities", "See the vulnerability list: update that software first.")
            else if (it.contains("CVE-")) f += F("MEDIUM", "Software version has known vulnerabilities", "See the list below; update if the version matches.")
        }
        val order = listOf("HIGH", "MEDIUM", "LOW")
        sb.insert(sb.indexOf("\n## "), buildString {
            appendLine("\n## Summary: ${f.count { it.sev == "HIGH" }} high · ${f.count { it.sev == "MEDIUM" }} medium · ${f.count { it.sev == "LOW" }} low")
            f.sortedBy { order.indexOf(it.sev) }.forEach { appendLine("- **${it.sev}** — ${it.title}: ${it.detail}") }
            if (f.isEmpty()) appendLine("- No problems found by these checks.")
        })
        sb.appendLine("\n---\n_Next step: fix the high items first. Tap \"Ask Arin to fix\" if this is your own site's code._")
        return sb.toString()
    }

    private fun tls(host: String, port: Int, f: MutableList<F>): String = runCatching {
        (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).use { s ->
            s.soTimeout = 12_000
            s.startHandshake()
            val sess = s.session
            val cert = sess.peerCertificates.firstOrNull() as? X509Certificate
            val days = cert?.let { ((it.notAfter.time - System.currentTimeMillis()) / 86_400_000L) }
            if (sess.protocol in setOf("TLSv1", "TLSv1.1", "SSLv3")) f += F("HIGH", "Old TLS (${sess.protocol})", "Use TLS 1.2 or 1.3 only.")
            if (days != null && days < 14) f += F(if (days < 0) "HIGH" else "MEDIUM", "Certificate expires in $days days", "Renew it.")
            "- Protocol: ${sess.protocol} · cipher ${sess.cipherSuite}\n" +
                (cert?.let { "- Certificate: ${it.subjectX500Principal.name}\n- Issuer: ${it.issuerX500Principal.name}\n- Valid until: ${it.notAfter} ($days days)\n" } ?: "")
        }
    }.getOrElse { e ->
        f += F("HIGH", "TLS handshake failed", "Phones couldn't make a trusted secure connection: ${e.message?.take(120)}")
        "- ⚠️ ${e.message}\n"
    }

    /** TLS check of any host:port (used by the network tool too). */
    fun tlsOnly(host: String, port: Int): String = tls(host, port, mutableListOf())
}
