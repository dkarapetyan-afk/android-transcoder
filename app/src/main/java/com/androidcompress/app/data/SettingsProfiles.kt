package com.androidcompress.app.data

import com.androidcompress.app.util.onFailureLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SettingsProfile(
    val id: String,
    val name: String,
    val settings: EncodeSettings,
)

object SettingsProfiles {
    const val MAX = 40
    const val NAME_MAX = 40

    fun sanitizeName(raw: String): String =
        raw.trim().replace(WHITESPACE, " ").take(NAME_MAX)

    fun encode(profiles: List<SettingsProfile>): String {
        val arr = JSONArray()
        for (profile in profiles) {
            arr.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("settings", JSONObject(SettingsJson.encode(profile.settings.withoutClip()))),
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<SettingsProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = sanitizeName(obj.optString("name"))
                    if (id.isEmpty() || name.isEmpty()) continue
                    val settingsObj = obj.optJSONObject("settings") ?: continue
                    add(SettingsProfile(id, name, SettingsJson.decode(settingsObj.toString()).withoutClip()))
                }
            }
        }.onFailureLog(TAG, "decode profiles").getOrElse { emptyList() }
    }

    fun save(
        existing: List<SettingsProfile>,
        name: String,
        settings: EncodeSettings,
        id: () -> String = { UUID.randomUUID().toString() },
    ): SaveResult {
        val cleaned = sanitizeName(name)
        if (cleaned.isEmpty()) return SaveResult.EmptyName
        val stored = settings.withoutClip()
        val match = existing.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
        val profile = if (match != null) {
            match.copy(name = cleaned, settings = stored)
        } else {
            if (existing.size >= MAX) return SaveResult.LimitReached
            SettingsProfile(id(), cleaned, stored)
        }
        val rest = existing.filterNot { it.id == profile.id }
        return SaveResult.Saved(listOf(profile) + rest, profile)
    }

    fun delete(existing: List<SettingsProfile>, id: String): List<SettingsProfile> =
        existing.filterNot { it.id == id }

    fun matching(
        profiles: List<SettingsProfile>,
        settings: EncodeSettings,
        job: CompressJob?,
    ): SettingsProfile? = profiles.firstOrNull { settings.matchesProfile(it.settings, job) }

    fun findByName(profiles: List<SettingsProfile>, name: String): SettingsProfile? {
        val cleaned = sanitizeName(name)
        if (cleaned.isEmpty()) return null
        return profiles.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
    }

    sealed class SaveResult {
        data class Saved(
            val profiles: List<SettingsProfile>,
            val profile: SettingsProfile,
        ) : SaveResult()
        data object EmptyName : SaveResult()
        data object LimitReached : SaveResult()
    }

    private val WHITESPACE = Regex("\\s+")
    private const val TAG = "SettingsProfiles"
}
