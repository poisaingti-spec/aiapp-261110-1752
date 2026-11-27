package com.arin.security

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Vulnerability intelligence (no AI): known CVEs for a product and version from the US National Vulnerability Database,
 * with CVSS severity, and a flag when CISA lists it as exploited in the wild (KEV). Results are cached on the phone.
 * Matches are by name and version, so they are "possibly affected — confirm the exact version", never a verdict.
 */
object VulnIntel {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val cache = mutableMapOf<String, String>()
    @Volatile private var kev: Set<String>? = null

    /** Product/version pairs found in banners and headers, e.g. "nginx/1.18.0", "OpenSSH_8.2p1", "Apache/2.4.41". */
    fun productsIn(text: String): List<Pair<String, String>> =
        Regex("""\b(nginx|Apache|OpenSSH|lighttpd|Microsoft-IIS|PHP|OpenSSL|Express|Jetty|Tomcat|Caddy|dropbear|vsFTPd|ProFTPD|Exim|Postfix|MiniUPnPd|Boa|GoAhead|thttpd|Werkzeug|gunicorn)[/_ -]v?(\d+(?:\.\d+){1,3}[a-z0-9]*)""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1] to it.groupValues[2] }.distinct().take(6).toList()

    private fun kevIds(): Set<String> = kev ?: runCatching {
        http.newCall(Request.Builder().url("https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json").build()).execute().use { r ->
            val arr = JSONObject(r.body!!.string()).getJSONArray("vulnerabilities")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("cveID") }.toSet()
        }
    }.getOrDefault(emptySet()).also { if (it.isNotEmpty()) kev = it }

    /** Markdown list of known CVEs for [product] [version] (newest and most severe first). */
    fun lookup(product: String, version: String): String {
        val key = "${product.lowercase()} $version"
        cache[key]?.let { return it }
        val url = "https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage=40&keywordSearch=" + java.net.URLEncoder.encode(key, "UTF-8")
        val out = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Arin-Security/1.0").build()).execute().use { r ->
                if (!r.isSuccessful) return@use "- NVD didn't answer (HTTP ${r.code}); try again later."
                val vulns = JSONObject(r.body!!.string()).optJSONArray("vulnerabilities") ?: return@use "- none listed"
                val kev = kevIds()
                val rows = (0 until vulns.length()).map { vulns.getJSONObject(it).getJSONObject("cve") }.map { c ->
                    val id = c.getString("id")
                    val m = c.optJSONObject("metrics")
                    val cvss = listOf("cvssMetricV31", "cvssMetricV30", "cvssMetricV2").firstNotNullOfOrNull { k -> m?.optJSONArray(k)?.optJSONObject(0)?.optJSONObject("cvssData") }
                    val score = cvss?.optDouble("baseScore") ?: 0.0
                    val desc = c.optJSONArray("descriptions")?.let { d -> (0 until d.length()).map { d.getJSONObject(it) }.firstOrNull { it.optString("lang") == "en" }?.optString("value") }.orEmpty()
                    Triple(id, score, desc) to (id in kev)
                }.sortedWith(compareByDescending<Pair<Triple<String, Double, String>, Boolean>> { it.second }.thenByDescending { it.first.second })
                if (rows.isEmpty()) "- none listed for this exact version" else rows.take(8).joinToString("\n") { (t, inKev) ->
                    "- " + (if (inKev) "🚨 **EXPLOITED IN THE WILD (CISA KEV)** " else "") + "**${t.first}** · CVSS ${"%.1f".format(t.second)} — ${t.third.take(160)}"
                } + (if (rows.size > 8) "\n- …and ${rows.size - 8} more" else "")
            }
        }.getOrElse { "- couldn't reach the vulnerability database: ${it.message}" }
        cache[key] = out
        return out
    }

    /** A vulnerability section for every product/version found in [text] ("" if none). */
    fun section(text: String, onStep: (String) -> Unit = {}): String {
        val found = productsIn(text)
        if (found.isEmpty()) return ""
        return "\n## Known vulnerabilities for the versions seen\n_Matched by name and version from the NVD; confirm the exact version and any backported fixes before acting._\n" +
            found.joinToString("\n") { (p, v) -> onStep("Looking up known vulnerabilities for $p $v"); "\n**$p $v**\n" + lookup(p, v) }
    }
}
