package com.openapk.translate.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CoreTest {
    @Test
    fun guard() {
        assertTrue(StringGuard.shouldTranslate("Settings", true))
        assertTrue(StringGuard.shouldTranslate("You have %1\$d new messages", true))
        assertFalse(StringGuard.shouldTranslate("light", true))
        assertFalse(StringGuard.shouldTranslate("https://example.com", true))
        assertFalse(StringGuard.shouldTranslate("com.example.app", true))
        assertFalse(StringGuard.shouldTranslate("default_channel", true))
        assertFalse(StringGuard.shouldTranslate("Настройки", true))
        assertFalse(StringGuard.isTranslatableName("google_api_key"))
        assertFalse(StringGuard.isTranslatableName("pref_key_theme"))
        assertTrue(StringGuard.isTranslatableName("action_settings"))
        assertTrue(StringGuard.isTranslatableName("app_name"))
    }

    @Test
    fun placeholders() = runBlocking {
        // a "bad" MT that mangles placeholders
        val t = SmartTranslator(true) { s -> "RU[" + s.replace("%1\$d", "% 1 $ d") + "]" }
        val r = t.translate("You have %1\$d new messages")!!
        assertTrue(r, r.contains("%1\$d"))
        assertEquals(StringGuard.tokens("You have %1\$d new messages"), StringGuard.tokens(r))
        // a translator that injects % must be rejected
        val bad = SmartTranslator(true) { "100%" }
        assertNull(bad.translate("Done"))
        val ml = SmartTranslator(true) { "RU($it)" }.translate("Line one\nLine two")!!
        assertEquals("RU(Line one)\nRU(Line two)", ml)
    }

    @Test
    fun roundTripApk() = runBlocking {
        val path = System.getenv("APK_IN")
        assumeTrue(path != null)
        val apk = File(path!!)
        val arsc = ApkProcessor.readArsc(apk)!!
        val ed = ArscEditor(arsc)
        println("targets: ${ed.targetTexts.size}")
        val tr = SmartTranslator(true) { "ЖЖ $it" }
        val unique = ed.targetTexts.values.toSet()
        val map = tr.translateAll(unique) { _, _, _ -> }
        println("translated: ${map.size}")
        val idx = ed.targetTexts.mapNotNull { (i, s) -> map[s]?.let { i to it } }.toMap()
        val newArsc = ed.build(idx)
        // re-parse result: must be readable and contain translations
        val ed2 = ArscEditor(newArsc)
        val sample = ed2.targetTexts.values.count { it.startsWith("ЖЖ ") }
        println("after: ${ed2.targetTexts.size}, translated visible: $sample")
        assertTrue(sample > 0)
        val out = File(System.getenv("APK_OUT") ?: "build/out.apk")
        val unsigned = File(out.path + ".unsigned")
        ApkProcessor.rebuild(apk, newArsc, unsigned)
        ApkProcessor.sign(unsigned, out, SignKeys.loadOrCreate(File("build/keys")))
        unsigned.delete()
        println("written ${out.absolutePath}")
    }
}
