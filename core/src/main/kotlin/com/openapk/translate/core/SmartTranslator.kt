package com.openapk.translate.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps a raw machine translator (ML Kit on the phone) and makes it safe for UI strings:
 * line-by-line, placeholders protected, results validated.
 */
class SmartTranslator(
    private val sourceIsLatin: Boolean,
    private val mt: suspend (String) -> String,
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun translate(text: String): String? {
        if (!StringGuard.shouldTranslate(text, sourceIsLatin)) return null
        val res = try {
            text.split('\n').map { translateLine(it) }.joinToString("\n")
        } catch (e: Exception) {
            return null
        }
        if (res == text || !StringGuard.safeResult(text, res)) return null
        return res
    }

    private suspend fun raw(s: String): String = cache[s] ?: mt(s).also { cache[s] = it }

    private suspend fun translateLine(line: String): String {
        if (line.none { it.isLetter() }) return line
        val lead = line.takeWhile { it.isWhitespace() }
        val trail = line.substring(lead.length).takeLastWhile { it.isWhitespace() }
        val core = line.substring(lead.length, line.length - trail.length)
        val toks = StringGuard.tokens(core)
        if (toks.isEmpty()) {
            val r = raw(core)
            return lead + (if (r.contains('%')) core else r) + trail
        }
        val whole = raw(core)
        if (StringGuard.tokens(whole) == toks && whole.count { it == '%' } == core.count { it == '%' }) {
            return lead + whole + trail
        }
        // fallback: translate the text between placeholders separately
        val sb = StringBuilder(lead)
        var last = 0
        for (m in StringGuard.TOKEN.findAll(core)) {
            sb.append(segment(core.substring(last, m.range.first)))
            sb.append(m.value)
            last = m.range.last + 1
        }
        sb.append(segment(core.substring(last)))
        return sb.append(trail).toString()
    }

    private suspend fun segment(seg: String): String {
        if (seg.count { it.isLetter() } < 2) return seg
        val lead = seg.takeWhile { it.isWhitespace() || it in ".,:;!?()\"'«»-–—" }
        val rest = seg.substring(lead.length)
        val trail = rest.takeLastWhile { it.isWhitespace() || it in ".,:;!?()\"'«»-–—" }
        val core = rest.substring(0, rest.length - trail.length)
        if (core.isEmpty()) return seg
        val r = raw(core)
        return lead + (if (r.contains('%') || r.isBlank()) core else r) + trail
    }

    /** Translate many unique strings in parallel. Returns original -> translated. */
    suspend fun translateAll(
        texts: Collection<String>,
        parallelism: Int = 4,
        onProgress: (done: Int, total: Int, sample: Pair<String, String>?) -> Unit,
    ): Map<String, String> = coroutineScope {
        val sem = Semaphore(parallelism)
        val done = AtomicInteger()
        val total = texts.size
        val jobs = texts.map { t ->
            async {
                sem.withPermit {
                    val r = translate(t)
                    onProgress(done.incrementAndGet(), total, r?.let { t to it })
                    r?.let { t to it }
                }
            }
        }
        jobs.mapNotNull { it.await() }.toMap()
    }
}
