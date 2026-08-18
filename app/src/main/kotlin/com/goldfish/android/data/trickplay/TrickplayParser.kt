package com.goldfish.android.data.trickplay

data class TrickplayFrame(
    val startMs: Long,
    val endMs: Long,
    val sprite: String,   // Dateiname relativ zum Trickplay-Endpoint, z.B. "sprite-0.jpg"
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

object TrickplayParser {
    private val timeRegex = Regex("""(\d+):(\d+):(\d+)\.(\d+)""")

    fun parse(vtt: String): List<TrickplayFrame> {
        val lines = vtt.lines()
        val frames = mutableListOf<TrickplayFrame>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val parts = line.split("-->").map { it.trim() }
                if (parts.size == 2) {
                    val start = parseTime(parts[0])
                    val end = parseTime(parts[1])
                    if (i + 1 < lines.size) {
                        parseFrame(lines[i + 1].trim(), start, end)?.let { frames.add(it) }
                    }
                }
                i += 2
            } else {
                i++
            }
        }
        return frames
    }

    fun frameAt(frames: List<TrickplayFrame>, positionMs: Long): TrickplayFrame? {
        if (frames.isEmpty()) return null
        // Binäre Suche wäre nicht falsch, aber bei <2000 Frames ist linear schnell genug
        return frames.firstOrNull { positionMs in it.startMs..it.endMs }
            ?: frames.lastOrNull { positionMs >= it.startMs }
            ?: frames.first()
    }

    private fun parseTime(t: String): Long {
        val m = timeRegex.matchEntire(t) ?: return 0L
        val (h, mn, s, ms) = m.destructured
        return h.toLong() * 3600_000 + mn.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
    }

    private fun parseFrame(line: String, start: Long, end: Long): TrickplayFrame? {
        // Erwartet: "sprite-0.jpg#xywh=0,0,160,90"
        val hashIdx = line.indexOf("#xywh=")
        if (hashIdx < 0) return null
        val sprite = line.substring(0, hashIdx)
        val coords = line.substring(hashIdx + 6).split(",").mapNotNull { it.toIntOrNull() }
        if (coords.size != 4) return null
        return TrickplayFrame(start, end, sprite, coords[0], coords[1], coords[2], coords[3])
    }
}
