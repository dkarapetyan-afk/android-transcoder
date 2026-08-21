package com.androidcompress.app.encode

data class ExtraArgsParse(
    val tokens: List<String>,
    val canonical: String,
    val error: String? = null,
) {
    val isValid: Boolean get() = error == null
}

/**
 * Extra FFmpeg flags only. Input/output paths stay under app control.
 */
object ExtraArgsSanitizer {

    private val blockedFlags = setOf(
        "i", "input",
        "filter_complex", "filter_complex_script", "lavfi", "filter_script",
        "protocol_whitelist", "protocol_blacklist",
        "attach", "dump_attachment",
        "progress", "report", "sdp_file", "stdin",
        "hwaccel_device", "init_hw_device",
        "recast_media",
        "y", "n",
        "h", "help", "version", "buildconf",
        "formats", "muxers", "demuxers", "devices", "codecs", "decoders", "encoders",
        "bsfs", "protocols", "filters", "pix_fmts", "layouts", "sample_fmts", "colors",
        "hide_banner",
        "pass", "passlogfile",
    )

    private val blockedValueHints = listOf(
        "movie=", "amovie=", "subtitles=", "ass=", "filename=",
        "concat:", "file:", "subfile:", "bluray:", "dvd:",
    )

    private val outputExt = Regex(
        """(?i).+\.(mp4|mkv|mov|webm|m4a|m4v|aac|wav|avi|ts|m3u8|mp3|flac|ogg)$""",
    )

    fun parse(raw: String): ExtraArgsParse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ExtraArgsParse(emptyList(), "")
        if (trimmed.length > 800) {
            return ExtraArgsParse(emptyList(), "", "Extra args are too long (800 character limit).")
        }
        val tokens = tokenize(trimmed)
            ?: return ExtraArgsParse(emptyList(), "", "Unmatched quote in extra args.")
        val cleaned = tokens.dropWhile { it.equals("ffmpeg", ignoreCase = true) }
        if (cleaned.isEmpty()) return ExtraArgsParse(emptyList(), "")
        if (cleaned.size > 40) {
            return ExtraArgsParse(emptyList(), "", "Too many extra arguments (40 token limit).")
        }
        val error = validate(cleaned)
        if (error != null) return ExtraArgsParse(emptyList(), "", error)
        return ExtraArgsParse(cleaned, join(cleaned))
    }

    fun insert(baseArgs: List<String>, extraRaw: String): List<String> {
        val parsed = parse(extraRaw)
        if (!parsed.isValid || parsed.tokens.isEmpty() || baseArgs.isEmpty()) return baseArgs
        return baseArgs.dropLast(1) + parsed.tokens + baseArgs.last()
    }

    internal fun tokenize(raw: String): List<String>? {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                quote != null -> {
                    if (c == '\\' && i + 1 < raw.length) {
                        buf.append(raw[i + 1])
                        i += 2
                        continue
                    }
                    if (c == quote) quote = null else buf.append(c)
                }
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> {
                    if (buf.isNotEmpty()) {
                        out += buf.toString()
                        buf.clear()
                    }
                }
                else -> buf.append(c)
            }
            i++
        }
        if (quote != null) return null
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun validate(tokens: List<String>): String? {
        var expectingValueFor: String? = null
        for (token in tokens) {
            if (expectingValueFor != null) {
                val flag = expectingValueFor
                expectingValueFor = null
                if (token.startsWith("-") && flagName(token) != null) {
                    val err = validateFlag(token)
                    if (err != null) return err
                    if (takesValue(flagName(token)!!)) expectingValueFor = token
                    continue
                }
                val err = validateValue(flag, token)
                if (err != null) return err
                continue
            }
            val name = flagName(token) ?: return "Extra args must be FFmpeg flags (got \"$token\")."
            val err = validateFlag(token)
            if (err != null) return err
            if (takesValue(name)) expectingValueFor = token
        }
        if (expectingValueFor != null) {
            return "Flag $expectingValueFor needs a value."
        }
        return null
    }

    private fun validateFlag(token: String): String? {
        val name = flagName(token) ?: return "Invalid flag \"$token\"."
        if (name in blockedFlags || name.startsWith("i:") || name == "map") {
            return "Flag -$name is not allowed in extra args."
        }
        return null
    }

    private fun validateValue(flag: String, value: String): String? {
        val lower = value.lowercase()
        if (value.contains("://") || value.contains('/') || value.contains('\\')) {
            return "Extra args cannot include file paths or URLs."
        }
        if (blockedValueHints.any { lower.contains(it) }) {
            return "Extra args cannot read other files (blocked value for $flag)."
        }
        val flagName = flagName(flag)
        if (flagName == "f" && (lower == "concat" || lower == "lavfi")) {
            return "Format $value is not allowed in extra args."
        }
        if (outputExt.matches(value)) {
            return "Extra args cannot set an output file."
        }
        return null
    }

    private fun flagName(token: String): String? {
        if (!token.startsWith("-") || token == "-" || token == "--") return null
        return token.trimStart('-').lowercase()
    }

    private fun takesValue(name: String): Boolean {
        val valueless = setOf(
            "an", "sn", "dn", "vn", "asn",
            "shortest", "copyts", "start_at_zero", "copytb",
            "ignidx", "fastseek",
        )
        return name !in valueless
    }

    private fun join(tokens: List<String>): String = tokens.joinToString(" ") { token ->
        if (token.any { it.isWhitespace() || it == '\'' || it == '"' }) {
            "\"${token.replace("\"", "\\\"")}\""
        } else {
            token
        }
    }
}
