package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileStoreTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("profile-store-test").toFile()
        return File(dir, "profiles.json")
    }

    @Test
    fun saveLoad_roundTrip() {
        val store = ProfileStore(tempFile())
        val p1 = ProfileCodec.defaultFreeFire()
        val p2 = ProfileCodec.defaultFreeFire().copy(name = "PUBG")
        store.save(listOf(p1, p2), p2.id)
        val loaded = store.load()
        assertEquals(listOf(p1, p2), loaded.profiles)
        assertEquals(p2.id, loaded.activeId)
    }

    @Test
    fun load_missingFile_returnsEmpty() {
        val loaded = ProfileStore(tempFile()).load()
        assertTrue(loaded.profiles.isEmpty())
        assertEquals(null, loaded.activeId)
    }

    @Test
    fun load_corruptFile_returnsEmpty() {
        val f = tempFile()
        f.writeText("{không phải json", Charsets.UTF_8)
        assertTrue(ProfileStore(f).load().profiles.isEmpty())
    }

    @Test
    fun load_oneBadProfile_othersKept() {
        val f = tempFile()
        val good = ProfileCodec.defaultFreeFire()
        // 1 profile tốt + 1 profile version mới hơn app (bị bỏ).
        val text = """{"version":1,"activeId":"${good.id}","profiles":[
            ${ProfileCodec.toJson(good)},
            {"version":99,"name":"Mới quá","elements":[]}
        ]}"""
        f.writeText(text, Charsets.UTF_8)
        val loaded = ProfileStore(f).load()
        assertEquals(listOf(good), loaded.profiles)
        assertEquals(good.id, loaded.activeId)
    }

    @Test
    fun load_unknownActiveId_fallsBackToFirst() {
        val f = tempFile()
        val good = ProfileCodec.defaultFreeFire()
        f.writeText(
            """{"version":1,"activeId":"id-khong-co","profiles":[${ProfileCodec.toJson(good)}]}""",
            Charsets.UTF_8,
        )
        assertEquals(good.id, ProfileStore(f).load().activeId)
    }

    @Test
    fun ensureDefault_createsWhenEmpty() {
        val store = ProfileStore(tempFile())
        val state = store.ensureDefault()
        assertEquals(1, state.profiles.size)
        assertEquals("Game mới", state.profiles[0].name)
        assertNull(state.profiles[0].packageName)
        // Profile đang dùng mặc định là profile duy nhất đó.
        assertEquals(state.profiles[0].id, state.activeId)
        // Lần 2 không tạo thêm.
        assertEquals(1, store.ensureDefault().profiles.size)
    }

    @Test
    fun ensureDefault_keepsExisting() {
        val store = ProfileStore(tempFile())
        val p = ProfileCodec.defaultFreeFire().copy(name = "Của tôi")
        store.save(listOf(p), p.id)
        val state = store.ensureDefault()
        assertEquals(listOf(p), state.profiles)
    }
}
