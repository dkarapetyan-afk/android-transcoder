package com.androidcompress.app.encode

data class CommandTemplateParse(
    val tokens: List<String>,
    val canonical: String,
    val error: String? = null,
) {
    val isValid: Boolean get() = error == null
}

/**
 * Editable FFmpeg argv with INPUT / OUTPUT placeholders.
 * Real file paths are injected only when encoding starts.
 */
object FfmpegCommandTemplate {
    const val INPUT = "INPUT"
    const val OUTPUT = "OUTPUT"

    private val blockedFlags = setOf(
        "filter_complex", "filter_complex_script", "lavfi", "filter_script",
        "protocol_whitelist", "protocol_blacklist",
        "attach", "dump_attachment",
        "progress", "report", "sdp_file", "stdin",
        "hwaccel_device", "init_hw_device",
        "recast_media",
        "h", "help", "version", "buildconf",
        "formats", "muxers", "demuxers", "devices", "codecs", "decoders", "encoders",
        "bsfs", "protocols", "filters", "pix_fmts", "layouts", "sample_fmts", "colors",
    )

    private val blockedValueHints = listOf(
        "movie=", "amovie=", "subtitles=", "ass=", "filename=",
        "concat:", "file:", "subfile:", "bluray:", "dvd:",
    )

    fun fromArgs(args: List<String>): String {
        if (args.isEmpty()) return ""
        val tokens = args.toMutableList()
        val inputAt = tokens.indexOfFirst { it == "-i" || it == "-input" }
        if (inputAt >= 0 && inputAt + 1 < tokens.size) {
            tokens[inputAt + 1] = INPUT
        }
        tokens[tokens.lastIndex] = OUTPUT
        return quoteArgs(tokens)
    }

    fun parse(raw: String): CommandTemplateParse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return CommandTemplateParse(emptyList(), "", "Command template is empty.")
        }
        if (trimmed.length > 2500) {
            return CommandTemplateParse(emptyList(), "", "Command template is too long (2500 character limit).")
        }
        val tokens = ExtraArgsSanitizer.tokenize(trimmed)
            ?: return CommandTemplateParse(emptyList(), "", "Unmatched quote in command template.")
        val cleaned = tokens.dropWhile { it.equals("ffmpeg", ignoreCase = true) }
        if (cleaned.isEmpty()) {
            return CommandTemplateParse(emptyList(), "", "Command template is empty.")
        }
        if (cleaned.size > 80) {
            return CommandTemplateParse(emptyList(), "", "Too many command tokens (80 token limit).")
        }
        val error = validate(cleaned)
        if (error != null) return CommandTemplateParse(emptyList(), "", error)
        return CommandTemplateParse(cleaned, quoteArgs(cleaned))
    }

    fun materialize(raw: String, input: String, output: String): Result<List<String>> = runCatching {
        val parsed = parse(raw)
        if (!parsed.isValid) error(parsed.error ?: "Invalid command template.")
        applyPaths(parsed.tokens.toMutableList(), input, output)
    }

    private fun applyPaths(tokens: MutableList<String>, input: String, output: String): List<String> {
        val inputFlags = tokens.withIndex().filter { (_, token) ->
            token == "-i" || token == "-input"
        }
        if (inputFlags.size > 1) error("Only one input (-i) is allowed.")
        if (inputFlags.isEmpty()) {
            tokens.addAll(0, listOf("-y", "-hide_banner", "-i", input))
        } else {
            val index = inputFlags.first().index
            if (index == tokens.lastIndex) error("-i needs a file value.")
            tokens[index + 1] = input
        }
        val last = tokens.last()
        if (last.startsWith("-") && last != "-" && last != "--") {
            tokens += output
        } else {
            tokens[tokens.lastIndex] = output
        }
        return tokens
    }

    private fun validate(tokens: List<String>): String? {
        var inputFlags = 0
        var expectingValueFor: String? = null
        tokens.forEachIndexed { index, token ->
            if (expectingValueFor != null) {
                val flag = expectingValueFor!!
                expectingValueFor = null
                if (flagName(token) != null) {
                    val err = validateFlag(token, inputFlags)
                    if (err != null) return err
                    if (isInputFlag(token)) inputFlags += 1
                    if (takesValue(flagName(token)!!)) expectingValueFor = token
                    return@forEachIndexed
                }
                val err = validateValue(flag, token, index == tokens.lastIndex)
                if (err != null) return err
                return@forEachIndexed
            }
            val name = flagName(token)
            if (name == null) {
                if (index == tokens.lastIndex) return@forEachIndexed
                return "Unexpected value \"$token\" in command template."
            }
            val err = validateFlag(token, inputFlags)
            if (err != null) return err
            if (isInputFlag(token)) inputFlags += 1
            if (takesValue(name)) expectingValueFor = token
        }
        if (inputFlags > 1) return "Only one input (-i) is allowed."
        return null
    }

    private fun validateFlag(token: String, inputFlags: Int): String? {
        val name = flagName(token) ?: return "Invalid flag \"$token\"."
        if (isInputFlag(token) && inputFlags >= 1) return "Only one input (-i) is allowed."
        if (name in blockedFlags || name.startsWith("i:") || name == "map") {
            return "Flag -$name is not allowed in the command template."
        }
        return null
    }

    private fun validateValue(flag: String, value: String, isLast: Boolean): String? {
        if (value == INPUT || value == OUTPUT) return null
        if (isInputFlag(flag) || isLast) return null
        val lower = value.lowercase()
        if (value.contains("://") || value.contains('/') || value.contains('\\')) {
            return "Command template cannot include extra file paths or URLs."
        }
        if (blockedValueHints.any { lower.contains(it) }) {
            return "Command template cannot read other files (blocked value for $flag)."
        }
        val name = flagName(flag)
        if (name == "f" && (lower == "concat" || lower == "lavfi")) {
            return "Format $value is not allowed in the command template."
        }
        return null
    }

    private fun isInputFlag(token: String): Boolean {
        val name = flagName(token) ?: return false
        return name == "i" || name == "input"
    }

    private fun flagName(token: String): String? {
        if (!token.startsWith("-") || token == "-" || token == "--") return null
        return token.trimStart('-').lowercase()
    }

    private fun takesValue(name: String): Boolean {
        val valueless = setOf(
            "y", "n", "hide_banner",
            "an", "sn", "dn", "vn", "asn",
            "shortest", "copyts", "start_at_zero", "copytb",
            "ignidx", "fastseek",
        )
        return name !in valueless
    }
}
