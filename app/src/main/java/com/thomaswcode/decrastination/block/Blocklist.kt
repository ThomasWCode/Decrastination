package com.thomaswcode.decrastination.block

/**
 * What is blocked, as decided on 8 Oct (Q8): video and social apps; in Chrome and Brave, the
 * same services' sites, read from the address bar; Firefox and Tor outright, since their address
 * bars aren't read. Never blocked: calls, messages, WhatsApp, Slack, Teams, school and study apps,
 * maps and travel, banking, authenticators, AI assistants, health and support apps, none of
 * which is on any list here.
 */
object Blocklist {
    val APPS = listOf(
        "com.google.android.youtube",
        // TikTok's two package names (global and Asian builds).
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.netflix.mediaclient",
        "bbc.iplayer.android",
        "tv.twitch.android.app",
        "com.naver.linewebtoon",
        "com.snapchat.android",
        "xyz.blueskyweb.app",
        "com.discord",
        "com.linkedin.android",
    )

    val SITES = listOf(
        "youtube.com",
        "youtu.be",
        "tiktok.com",
        "netflix.com",
        "bbc.co.uk/iplayer",
        "twitch.tv",
        "webtoons.com",
        "snapchat.com",
        "bsky.app",
        "discord.com",
        "linkedin.com",
    )

    /** Browsers whose address bar has the view id `<package>:id/url_bar` (Chromium's). */
    val CHECKED_BROWSERS = listOf("com.android.chrome", "com.brave.browser")
    val BLOCKED_BROWSERS = listOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.torproject.torbrowser")

    /** An address, as a browser's address bar shows it. */
    data class Address(val host: String, val path: String)

    /**
     * The address in an address bar's text: "m.youtube.com/watch?v=…", "https://www.twitch.tv",
     * "bbc.co.uk/iplayer". Null for anything that isn't one, such as a search being typed.
     */
    fun address(text: String): Address? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val rest = trimmed.substringAfter("://", trimmed)
        val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringBefore(':').lowercase().trimEnd('.')
        if ('.' !in host || host.startsWith('.') || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        val path = rest.drop(rest.takeWhile { it != '/' && it != '?' && it != '#' }.length).takeWhile { it != '?' && it != '#' }
        return Address(host, path.ifEmpty { "/" })
    }

    /** The blocked site [address] is on, or null. A site matches its subdomains, and a path its sub-paths. */
    fun blockedSite(address: Address, sites: List<String>): String? = sites.firstOrNull { site ->
        val host = site.substringBefore('/').lowercase()
        val path = site.drop(host.length).lowercase()
        (address.host == host || address.host.endsWith(".$host")) && (path.isEmpty() || address.path.lowercase().startsWith(path))
    }

    /** The view id of a checked browser's address bar. */
    fun urlBarId(browser: String): String = "$browser:id/url_bar"
}
