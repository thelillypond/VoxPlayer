package com.voxfeed.app.data

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Minimal pure-JVM MP4/M4A tagger: inserts moov/udta/meta/ilst (©nam, ©ART, ©alb, covr) without FFmpeg.
 * Handles progressive and fragmented files; shifts stco/co64 when sample data sits after moov.
 */
object Mp4Tagger {
    class Meta(val title: String, val artist: String, val album: String, val cover: ByteArray?)

    private val L1 = Charsets.ISO_8859_1

    private fun int(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val b = ByteBuffer.allocate(size).putInt(size).put(type.toByteArray(L1))
        parts.forEach { b.put(it) }
        return b.array()
    }
    private fun dataBox(flags: Int, payload: ByteArray) = box("data", int(flags), int(0), payload)
    private fun textItem(type: String, value: String) = box(type, dataBox(1, value.toByteArray(Charsets.UTF_8)))

    private fun buildMeta(m: Meta): ByteArray {
        val items = ArrayList<ByteArray>()
        items += textItem("\u00A9nam", m.title)
        items += textItem("\u00A9ART", m.artist)
        items += textItem("\u00A9alb", m.album)
        m.cover?.takeIf { it.size > 8 }?.let { c ->
            val png = c[0] == 0x89.toByte() && c[1] == 'P'.code.toByte()
            items += box("covr", dataBox(if (png) 14 else 13, c))
        }
        val hdlr = box("hdlr", int(0), int(0), "mdir".toByteArray(L1), ByteArray(12), ByteArray(1))
        return box("meta", int(0), hdlr, box("ilst", *items.toTypedArray()))
    }

    private data class Child(val pos: Int, val size: Int, val type: String)

    private fun children(buf: ByteArray, start: Int, end: Int): List<Child> {
        val out = ArrayList<Child>()
        var p = start
        while (p + 8 <= end) {
            val size = ByteBuffer.wrap(buf, p, 4).int
            if (size < 8 || p + size > end) break
            out += Child(p, size, String(buf, p + 4, 4, L1))
            p += size
        }
        return out
    }

    private fun shiftOffsets(buf: ByteArray, start: Int, end: Int, delta: Int) {
        for (c in children(buf, start, end)) {
            when (c.type) {
                "trak", "mdia", "minf", "stbl" -> shiftOffsets(buf, c.pos + 8, c.pos + c.size, delta)
                "stco" -> {
                    val bb = ByteBuffer.wrap(buf)
                    val n = bb.getInt(c.pos + 12)
                    for (i in 0 until n) { val o = c.pos + 16 + i * 4; bb.putInt(o, bb.getInt(o) + delta) }
                }
                "co64" -> {
                    val bb = ByteBuffer.wrap(buf)
                    val n = bb.getInt(c.pos + 12)
                    for (i in 0 until n) { val o = c.pos + 16 + i * 8; bb.putLong(o, bb.getLong(o) + delta) }
                }
            }
        }
    }

    /** Writes the tagged copy of [src] to [out]. Returns false (writing nothing) if the file can't be tagged safely. */
    fun tag(src: File, out: OutputStream, meta: Meta): Boolean = RandomAccessFile(src, "r").use { raf ->
        val len = raf.length()
        var pos = 0L
        var moovPos = -1L; var moovSize = 0L; var mdatPos = -1L
        while (pos + 8 <= len) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(L1)
            if (size == 1L) return false // 64-bit box sizes: leave untouched
            if (size == 0L) size = len - pos
            if (size < 8 || pos + size > len) return false
            if (type == "moov" && moovPos < 0) { moovPos = pos; moovSize = size }
            if (type == "mdat" && mdatPos < 0) mdatPos = pos
            pos += size
        }
        if (moovPos < 0 || moovSize > 64L * 1024 * 1024) return false
        val moov = ByteArray(moovSize.toInt()).also { raf.seek(moovPos); raf.readFully(it) }
        val metaBox = buildMeta(meta)
        val kids = children(moov, 8, moov.size)
        val udta = kids.firstOrNull { it.type == "udta" }
        val newMoov: ByteArray
        val delta: Int
        if (udta == null) {
            val box = box("udta", metaBox)
            delta = box.size
            newMoov = moov + box
        } else {
            if (children(moov, udta.pos + 8, udta.pos + udta.size).any { it.type == "meta" }) return false
            delta = metaBox.size
            val end = udta.pos + udta.size
            newMoov = moov.copyOfRange(0, end) + metaBox + moov.copyOfRange(end, moov.size)
            ByteBuffer.wrap(newMoov).putInt(udta.pos, udta.size + delta)
        }
        ByteBuffer.wrap(newMoov).putInt(0, newMoov.size)
        if (mdatPos > moovPos) shiftOffsets(newMoov, 8, newMoov.size, delta)

        val buf = ByteArray(64 * 1024)
        fun copy(from: Long, to: Long) {
            raf.seek(from)
            var left = to - from
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n); left -= n
            }
        }
        copy(0, moovPos)
        out.write(newMoov)
        copy(moovPos + moovSize, len)
        true
    }
}
