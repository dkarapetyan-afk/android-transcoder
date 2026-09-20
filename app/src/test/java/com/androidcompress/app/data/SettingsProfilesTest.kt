package com.androidcompress.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsProfilesTest {

    @Test
    fun saveRoundTripAndOverwriteByName() {
        val first = EncodeSettings.forPreset(Preset.SMALLER).copy(
            grayscale = true,
            clipStartMs = 1_000,
            clipEndMs = 4_000,
        )
        val saved = SettingsProfiles.save(emptyList(), "  Discord  10MB ", first) { "id-1" }
            as SettingsProfiles.SaveResult.Saved
        assertEquals("Discord 10MB", saved.profile.name)
        assertEquals("id-1", saved.profile.id)
        assertEquals(0L, saved.profile.settings.clipStartMs)
        assertNull(saved.profile.settings.clipEndMs)
        assertTrue(saved.profile.settings.grayscale)

        val decoded = SettingsProfiles.decode(SettingsProfiles.encode(saved.profiles))
        assertEquals(saved.profiles, decoded)

        val second = EncodeSettings.forPreset(Preset.HIGHER).copy(captions = true)
        val replaced = SettingsProfiles.save(decoded, "discord 10mb", second) { "id-2" }
            as SettingsProfiles.SaveResult.Saved
        assertEquals(1, replaced.profiles.size)
        assertEquals("id-1", replaced.profile.id)
        assertEquals("discord 10mb", replaced.profile.name)
        assertTrue(replaced.profile.settings.captions)
        assertEquals(Preset.HIGHER, replaced.profile.settings.preset)
    }

    @Test
    fun emptyNameAndLimit() {
        assertEquals(
            SettingsProfiles.SaveResult.EmptyName,
            SettingsProfiles.save(emptyList(), "   ", EncodeSettings()),
        )
        val existing = (1..SettingsProfiles.MAX).map { i ->
            SettingsProfile("id-$i", "P$i", EncodeSettings.forPreset(Preset.BALANCED))
        }
        assertEquals(
            SettingsProfiles.SaveResult.LimitReached,
            SettingsProfiles.save(existing, "another", EncodeSettings()),
        )
        val overwritten = SettingsProfiles.save(existing, "p1", EncodeSettings.forPreset(Preset.SMALLER))
        assertTrue(overwritten is SettingsProfiles.SaveResult.Saved)
    }

    @Test
    fun decodeSkipsBrokenEntries() {
        val raw = """
            [
              {"id":"ok","name":"Good","settings":{"preset":"SMALLER","maxHeight":720,"fpsCap":30,
                "codec":"H264","preferHardware":true,"videoBitrateKbps":1500,"audio":"AAC_96"}},
              {"id":"","name":"NoId","settings":{}},
              {"name":"NoSettings"},
              "not-an-object"
            ]
        """.trimIndent()
        val decoded = SettingsProfiles.decode(raw)
        assertEquals(listOf("ok"), decoded.map { it.id })
        assertEquals(Preset.SMALLER, decoded.single().settings.preset)
    }

    @Test
    fun applyKeepsClipAndConstrainsCombine() {
        val current = EncodeSettings.forPreset(Preset.BALANCED).copy(
            clipStartMs = 2_000,
            clipEndMs = 8_000,
            output = OutputMode.AUDIO,
        )
        val profile = EncodeSettings.forPreset(Preset.SMALLER).copy(
            engine = EncodeEngine.MEDIA3,
            grayscale = true,
            captions = true,
            burnCaptions = true,
            audio = AudioOption.MUTE,
            output = OutputMode.AUDIO,
            targetSizePreset = TargetSizePreset.DISCORD,
            targetSizeBytes = TargetSizePreset.DISCORD.bytes,
        )
        val job = CompressJob(
            id = "c",
            type = JobType.COMBINE,
            status = JobStatus.READY,
            sourceUri = "content://video",
            outputUri = null,
            displayName = "pair",
            sourceBytes = 1,
            outputBytes = null,
            durationMs = 10_000,
            width = 1920,
            height = 1080,
            settingsJson = "{}",
            error = null,
            createdAt = 1,
            finishedAt = null,
            audioUri = "content://audio",
        )
        val applied = current.withProfile(profile).constrainedTo(job)
        assertEquals(2_000L, applied.clipStartMs)
        assertEquals(8_000L, applied.clipEndMs)
        assertEquals(OutputMode.VIDEO, applied.output)
        assertEquals(AudioOption.AAC_128, applied.audio)
        assertTrue(applied.grayscale)
        assertTrue(applied.matchesProfile(profile, job))
        assertEquals("Good", SettingsProfiles.matching(
            listOf(SettingsProfile("1", "Good", profile.withoutClip())),
            applied,
            job,
        )?.name)
    }

    @Test
    fun deleteRemovesById() {
        val profiles = listOf(
            SettingsProfile("a", "One", EncodeSettings()),
            SettingsProfile("b", "Two", EncodeSettings()),
        )
        assertEquals(listOf("b"), SettingsProfiles.delete(profiles, "a").map { it.id })
    }
}
