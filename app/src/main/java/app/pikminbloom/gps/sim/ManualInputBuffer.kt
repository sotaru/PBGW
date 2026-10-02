package app.pikminbloom.gps.sim

import app.pikminbloom.gps.geo.GeoMath
import java.util.ArrayDeque

/** Keeps the duration of touch inputs, including gestures released between engine ticks. */
class ManualInputBuffer {
    data class Slice(val durationMs: Long, val bearingDeg: Double, val magnitude: Double)

    private val pending = ArrayDeque<Slice>()
    private var pendingMs = 0L
    private var active = false
    private var lastMs = 0L
    private var bearing = 0.0
    private var magnitude = 0.0

    /** A new manual session never replays input from a pause, relocation, or previous run. */
    @Synchronized fun start(nowMs: Long, bearingDeg: Double, magnitude: Double) {
        pending.clear()
        pendingMs = 0L
        active = true
        lastMs = nowMs
        setVector(bearingDeg, magnitude)
    }

    @Synchronized fun update(nowMs: Long, bearingDeg: Double, magnitude: Double) {
        appendUntil(nowMs)
        setVector(bearingDeg, magnitude)
    }

    /** Exactly-once consumption; a stalled engine can catch up at most three seconds. */
    @Synchronized fun consume(nowMs: Long): List<Slice> {
        appendUntil(nowMs)
        val result = pending.toList()
        pending.clear()
        pendingMs = 0L
        return result
    }

    @Synchronized fun stop() {
        active = false
        pending.clear()
        pendingMs = 0L
    }

    private fun setVector(bearingDeg: Double, value: Double) {
        bearing = if (bearingDeg.isFinite()) GeoMath.normalizeBearing(bearingDeg) else 0.0
        magnitude = if (bearingDeg.isFinite() && value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
    }

    private fun appendUntil(nowMs: Long) {
        if (!active || nowMs <= lastMs) return
        val duration = (nowMs - lastMs).coerceAtMost(MAX_PENDING_MS)
        lastMs = nowMs
        val previous = pending.peekLast()
        if (previous != null && previous.bearingDeg == bearing && previous.magnitude == magnitude) {
            pending.removeLast()
            pending.addLast(previous.copy(durationMs = previous.durationMs + duration))
        } else {
            pending.addLast(Slice(duration, bearing, magnitude))
        }
        pendingMs += duration
        while (pendingMs > MAX_PENDING_MS || pending.size > MAX_SLICES) {
            val first = pending.removeFirst()
            val excess = pendingMs - MAX_PENDING_MS
            if (pending.size < MAX_SLICES && excess in 1 until first.durationMs) {
                pending.addFirst(first.copy(durationMs = first.durationMs - excess))
                pendingMs -= excess
            } else {
                pendingMs -= first.durationMs
            }
        }
    }

    companion object {
        private const val MAX_PENDING_MS = 3000L
        private const val MAX_SLICES = 512
    }
}
