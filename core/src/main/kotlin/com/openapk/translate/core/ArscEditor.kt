package com.openapk.translate.core

import java.io.ByteArrayOutputStream

class ArscException(message: String) : Exception(message)

internal fun u8(d: ByteArray, p: Int): Int = d[p].toInt() and 0xFF
internal fun u16(d: ByteArray, p: Int): Int = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
internal fun u32(d: ByteArray, p: Int): Int =
    (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
        ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

internal fun put16(d: ByteArray, p: Int, v: Int) {
    d[p] = v.toByte(); d[p + 1] = (v ushr 8).toByte()
}

internal fun put32(d: ByteArray, p: Int, v: Int) {
    d[p] = v.toByte(); d[p + 1] = (v ushr 8).toByte(); d[p + 2] = (v ushr 16).toByte(); d[p + 3] = (v ushr 24).toByte()
}

/** ResStringPool chunk reader / rewriter. */
class StringPool private constructor(private val d: ByteArray, val start: Int) {
    val headerSize = u16(d, start + 2)
    val size = u32(d, start + 4)
    val count = u32(d, start + 8)
    val styleCount = u32(d, start + 12)
    val flags = u32(d, start + 16)
    private val stringsStart = u32(d, start + 20)
    private val stylesStart = u32(d, start + 24)
    val isUtf8 get() = flags and UTF8_FLAG != 0

    private val stringsEnd: Int =
        start + if (styleCount > 0 && stylesStart > 0) stylesStart else size

    init {
        if (u16(d, start) != 0x0001) throw ArscException("not a string pool")
        if (count < 0 || styleCount < 0 || start + size > d.size ||
            start + headerSize + (count.toLong() + styleCount) * 4 > start + size
        ) throw ArscException("broken string pool")
    }

    private fun offset(i: Int) = start + stringsStart + u32(d, start + headerSize + i * 4)

    /** absolute [from, to) of the encoded string i, including length prefix and terminator. */
    private fun rawRange(i: Int): IntRange? {
        val p = offset(i)
        if (p < start || p >= stringsEnd) return null
        return try {
            if (isUtf8) {
                var q = p
                if (u8(d, q) and 0x80 != 0) q += 2 else q += 1
                var n8 = u8(d, q); q++
                if (n8 and 0x80 != 0) { n8 = ((n8 and 0x7F) shl 8) or u8(d, q); q++ }
                val end = q + n8 + 1
                if (end > stringsEnd) null else p until end
            } else {
                var q = p
                var n = u16(d, q); q += 2
                if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(d, q); q += 2 }
                val end = q + n * 2 + 2
                if (n < 0 || end > stringsEnd) null else p until end
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    fun get(i: Int): String? {
        if (i < 0 || i >= count) return null
        val r = rawRange(i) ?: return null
        var q = r.first
        return if (isUtf8) {
            q += if (u8(d, q) and 0x80 != 0) 2 else 1
            q += if (u8(d, q) and 0x80 != 0) 2 else 1
            String(d, q, r.last - q, Charsets.UTF_8)
        } else {
            q += if (u16(d, q) and 0x8000 != 0) 4 else 2
            String(d, q, r.last + 1 - 2 - q, Charsets.UTF_16LE)
        }
    }

    /** true if string i carries style spans (bold/italic etc). */
    fun hasStyle(i: Int): Boolean {
        if (i >= styleCount) return false
        val p = start + stylesStart + u32(d, start + headerSize + count * 4 + i * 4)
        if (p + 4 > start + size) return false
        return u32(d, p) != -1
    }

    fun rebuild(replace: Map<Int, String>, append: List<String>): ByteArray {
        val newCount = count + append.size
        val strings = ByteArrayOutputStream(size + append.size * 64)
        val offsets = IntArray(newCount)
        for (i in 0 until newCount) {
            offsets[i] = strings.size()
            val repl = if (i < count) replace[i] else append[i - count]
            if (repl != null) {
                strings.write(encode(repl))
            } else {
                val r = rawRange(i) ?: throw ArscException("can't read string #$i")
                strings.write(d, r.first, r.last - r.first + 1)
            }
        }
        while (strings.size() % 4 != 0) strings.write(0)
        val styleData = if (styleCount > 0 && stylesStart > 0)
            d.copyOfRange(start + stylesStart, start + size) else ByteArray(0)

        val newStringsStart = headerSize + newCount * 4 + styleCount * 4
        val total = newStringsStart + strings.size() + styleData.size
        val out = ByteArray(total)
        System.arraycopy(d, start, out, 0, headerSize)
        put32(out, 4, total)
        put32(out, 8, newCount)
        put32(out, 16, flags and SORTED_FLAG.inv())
        put32(out, 20, newStringsStart)
        put32(out, 24, if (styleData.isNotEmpty()) newStringsStart + strings.size() else 0)
        for (i in 0 until newCount) put32(out, headerSize + i * 4, offsets[i])
        System.arraycopy(d, start + headerSize + count * 4, out, headerSize + newCount * 4, styleCount * 4)
        System.arraycopy(strings.toByteArray(), 0, out, newStringsStart, strings.size())
        System.arraycopy(styleData, 0, out, newStringsStart + strings.size(), styleData.size)
        return out
    }

    private fun encode(s: String): ByteArray {
        val o = ByteArrayOutputStream()
        if (isUtf8) {
            val b = s.toByteArray(Charsets.UTF_8)
            fun len(v: Int) {
                if (v > 0x7FFF) throw ArscException("string too long")
                if (v > 0x7F) { o.write(0x80 or (v shr 8)); o.write(v and 0xFF) } else o.write(v)
            }
            len(s.length); len(b.size); o.write(b); o.write(0)
        } else {
            val n = s.length
            fun w16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            if (n > 0x7FFF) { w16(0x8000 or (n shr 16)); w16(n and 0xFFFF) } else w16(n)
            o.write(s.toByteArray(Charsets.UTF_16LE)); w16(0)
        }
        return o.toByteArray()
    }

    companion object {
        const val UTF8_FLAG = 0x100
        const val SORTED_FLAG = 0x1
        fun parse(d: ByteArray, start: Int) = StringPool(d, start)
    }
}

/**
 * Reads a compiled resources.arsc, finds the user-visible strings of the default
 * locale (string / string-array / plurals) and writes a new table with translations.
 * Strings that are shared with non-target resources (styles, other locales, styled
 * text) are cloned to new pool entries, so nothing else is affected.
 */
class ArscEditor(input: ByteArray) {
    private val data = input
    private val tableHeader: Int
    private val tableSize: Int
    private val pool: StringPool
    private val poolStart: Int

    private class Ref(val dataOffset: Int, val index: Int, val target: Boolean)

    private val refs = ArrayList<Ref>()
    private val byIndex: Map<Int, List<Ref>>

    /** pool index -> original text, for every translatable candidate. */
    val targetTexts: Map<Int, String>

    /** resource entry name for each target index (for diagnostics). */
    val targetNames = HashMap<Int, String>()

    init {
        if (data.size < 12 || u16(data, 0) != 0x0002) throw ArscException("not a resources.arsc")
        tableHeader = u16(data, 2)
        tableSize = u32(data, 4)
        val end = minOf(tableSize, data.size)
        var globalPool: StringPool? = null
        var gStart = -1
        var p = tableHeader
        while (p + 8 <= end) {
            val type = u16(data, p)
            val sz = u32(data, p + 4)
            if (sz < 8 || p.toLong() + sz > end) throw ArscException("broken chunk at $p")
            when (type) {
                0x0001 -> if (globalPool == null) { globalPool = StringPool.parse(data, p); gStart = p }
                0x0200 -> parsePackage(p)
            }
            p += sz
        }
        pool = globalPool ?: throw ArscException("no global string pool")
        poolStart = gStart
        byIndex = refs.groupBy { it.index }
        val tt = LinkedHashMap<Int, String>()
        for ((idx, list) in byIndex) {
            if (list.none { it.target }) continue
            val s = pool.get(idx) ?: continue
            tt[idx] = s
        }
        targetTexts = tt
    }

    private fun parsePackage(pkg: Int) {
        val hs = u16(data, pkg + 2)
        val sz = u32(data, pkg + 4)
        val typePool = StringPool.parse(data, pkg + u32(data, pkg + 268))
        val keyPool = StringPool.parse(data, pkg + u32(data, pkg + 276))
        val typeNames = Array(typePool.count) { typePool.get(it) ?: "" }
        val end = pkg + sz
        var p = pkg + hs
        while (p + 8 <= end) {
            val t = u16(data, p)
            val s = u32(data, p + 4)
            if (s < 8 || p.toLong() + s > end) break
            if (t == 0x0201) parseType(p, typeNames, keyPool)
            p += s
        }
    }

    private fun parseType(c: Int, typeNames: Array<String>, keys: StringPool) {
        val hs = u16(data, c + 2)
        val chunkEnd = c + u32(data, c + 4)
        val id = u8(data, c + 8)
        val flags = u8(data, c + 9)
        val entryCount = u32(data, c + 12)
        val entriesStart = u32(data, c + 16)
        val cfg = c + 20
        val defaultLocale = u8(data, cfg + 8) == 0 && u8(data, cfg + 9) == 0
        val typeName = typeNames.getOrNull(id - 1) ?: return
        val targetType = typeName == "string" || typeName == "array" || typeName == "plurals"
        val offs = c + hs
        val sparse = flags and 0x01 != 0
        val off16 = flags and 0x02 != 0

        for (i in 0 until entryCount) {
            val entryOff: Int = when {
                sparse -> u16(data, offs + i * 4 + 2) * 4
                off16 -> { val v = u16(data, offs + i * 2); if (v == 0xFFFF) continue; v * 4 }
                else -> { val v = u32(data, offs + i * 4); if (v == -1) continue; v }
            }
            val e = c + entriesStart + entryOff
            if (e < c || e + 8 > chunkEnd) continue
            val eSize = u16(data, e)
            val eFlags = u16(data, e + 2)
            if (eFlags and 0x0008 != 0) { // compact entry
                val type = (eFlags ushr 8) and 0xFF
                if (type == TYPE_STRING) add(e + 4, targetType && defaultLocale, keys, eSize)
                continue
            }
            val key = u32(data, e + 4)
            if (eFlags and 0x0001 != 0) { // bag
                if (e + 16 > chunkEnd) continue
                val cnt = u32(data, e + 12)
                var m = e + eSize
                for (j in 0 until cnt) {
                    if (m + 12 > chunkEnd) break
                    if (u8(data, m + 7) == TYPE_STRING) add(m + 8, targetType && defaultLocale, keys, key)
                    m += 12
                }
            } else {
                val v = e + eSize
                if (v + 8 > chunkEnd) continue
                if (u8(data, v + 3) == TYPE_STRING) add(v + 4, targetType && defaultLocale, keys, key)
            }
        }
    }

    private fun add(dataOffset: Int, candidate: Boolean, keys: StringPool, key: Int) {
        val idx = u32(data, dataOffset)
        var target = false
        if (candidate) {
            val name = keys.get(key) ?: ""
            target = StringGuard.isTranslatableName(name)
            if (target) targetNames[idx] = name
        }
        refs.add(Ref(dataOffset, idx, target))
    }

    /** @param translations pool index -> new text */
    fun build(translations: Map<Int, String>): ByteArray {
        val work = data.copyOf()
        val replace = HashMap<Int, String>()
        val append = ArrayList<String>()
        for ((idx, text) in translations) {
            val list = byIndex[idx] ?: continue
            if (list.none { it.target }) continue
            if (list.all { it.target } && !pool.hasStyle(idx)) {
                replace[idx] = text
            } else {
                val newIdx = pool.count + append.size
                append.add(text)
                for (r in list) if (r.target) put32(work, r.dataOffset, newIdx)
            }
        }
        val newPool = pool.rebuild(replace, append)
        val tail = poolStart + pool.size
        val out = ByteArray(work.size - pool.size + newPool.size)
        System.arraycopy(work, 0, out, 0, poolStart)
        System.arraycopy(newPool, 0, out, poolStart, newPool.size)
        System.arraycopy(work, tail, out, poolStart + newPool.size, work.size - tail)
        put32(out, 4, tableSize + (newPool.size - pool.size))
        return out
    }

    companion object {
        const val TYPE_STRING = 0x03
    }
}
