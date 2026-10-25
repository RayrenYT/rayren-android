package com.rayren.domain

sealed interface ImportInput {
    data class Remote(val url: String) : ImportInput {
        override fun toString() = "ImportInput.Remote"
    }
    data class Inline(val text: String) : ImportInput {
        override fun toString() = "ImportInput.Inline"
    }
}

enum class ImportStage { INPUT, DOWNLOAD, VERIFY, PARSE, SAVE }

/** Shared by clipboard, QR, share and manual import. Never log the input. */
object ImportInputResolver {
    private val link = Regex("(?i)(?:https?://|rayren://)[^\\s<>]+")
    private val server = Regex("(?i)^(?:vless|vmess|trojan|ss|hy2|hysteria2|openflux|olcrtc)://")

    fun resolve(input: String): ImportInput {
        ensure(input.length <= ImportLimits.MAX_BYTES, ImportLimits.SIZE_MESSAGE, "INPUT_TOO_LARGE")
        val text = input.trim().removePrefix("\uFEFF").trim()
        ensure(text.isNotEmpty(), "Пустая подписка", "EMPTY_INPUT")
        // A JSON document or a profile may itself contain HTTPS endpoints.
        if (text.startsWith('{') || text.startsWith('[') || text.lineSequence().any { server.containsMatchIn(it.trim()) }) return ImportInput.Inline(text)
        val matches = link.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return ImportInput.Inline(text)
        ensure(matches.size == 1, "В тексте несколько ссылок подписки. Вставьте одну нужную ссылку.", "MULTIPLE_SUBSCRIPTION_URLS")
        val url = ImportParser.subscriptionUrl(matches.single())
        return ImportInput.Remote(url)
    }
}
