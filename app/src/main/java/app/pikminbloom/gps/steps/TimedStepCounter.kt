package app.pikminbloom.gps.steps

import kotlin.random.Random
import app.pikminbloom.gps.geo.GpsMotion

/** Counts requested steps by active elapsed time; it does not measure physical walking. */
class TimedStepCounter(
    val target: Long,
    val stepsPerMinute: Int,
    jitterPct: Double = 0.0,
    maxCadenceSpm: Int = 0,
    private val random: Random = Random.Default,
) {
    private var stepMillis = 0.0
    private var rateWindowRemainingMs = 0L
    private var maxCadenceSpm = maxCadenceSpm
    var jitterPct = jitterPct
        private set
    var currentRate = stepsPerMinute.toDouble()
        private set
    var paused = false
    var motion: GpsMotion = GpsMotion.UNKNOWN
        private set

    init {
        require(target in 1..MAX_TARGET)
        require(stepsPerMinute in 1..MAX_RATE)
        validateVariation(jitterPct, maxCadenceSpm)
        redrawRate()
    }

    val generated: Long get() = (stepMillis / 60_000.0).toLong().coerceAtMost(target)
    val complete: Boolean get() = generated == target

    /** Apply the shared speed-jitter setting live, including turning jitter off. */
    fun updateRateVariation(jitterPct: Double, maxCadenceSpm: Int, motion: GpsMotion = this.motion) {
        validateVariation(jitterPct, maxCadenceSpm)
        if (this.jitterPct == jitterPct && this.maxCadenceSpm == maxCadenceSpm && this.motion == motion) return
        this.jitterPct = jitterPct
        this.maxCadenceSpm = maxCadenceSpm
        this.motion = motion
        redrawRate()
    }

    fun advance(elapsedMs: Long): Long {
        require(elapsedMs >= 0)
        val before = generated
        if (paused || complete || elapsedMs == 0L) return 0
        if (jitterPct == 0.0) {
            addElapsed(elapsedMs)
        } else {
            var remaining = elapsedMs
            // Integrate each rate window separately, so tick sizes do not change the random walk.
            while (remaining > 0 && !complete) {
                if (rateWindowRemainingMs == 0L) redrawRate()
                val segment = minOf(remaining, rateWindowRemainingMs)
                addElapsed(segment)
                rateWindowRemainingMs -= segment
                remaining -= segment
            }
        }
        return generated - before
    }

    private fun addElapsed(elapsedMs: Long) {
        stepMillis = (stepMillis + elapsedMs.toDouble() * currentRate).coerceAtMost(target * 60_000.0)
    }

    private fun redrawRate() {
        val variation = jitterPct / 100.0
        val factor = if (variation > 0.0) {
            val first = random.nextDouble(-variation, variation)
            val offset = when (motion) {
                GpsMotion.MOVING -> maxOf(first, random.nextDouble(-variation, variation))
                GpsMotion.STATIONARY -> minOf(first, random.nextDouble(-variation, variation))
                GpsMotion.UNKNOWN -> first
            }
            1.0 + offset
        } else 1.0
        val limit = if (maxCadenceSpm > 0) maxCadenceSpm.toDouble() else Double.POSITIVE_INFINITY
        currentRate = (stepsPerMinute * factor).coerceAtMost(limit)
        // Use the same 3–8 second variation cadence as simulated walking. Pauses freeze this clock.
        rateWindowRemainingMs = if (variation > 0.0) random.nextLong(3000, 8001) else 0
    }

    private fun validateVariation(jitterPct: Double, maxCadenceSpm: Int) {
        require(jitterPct.isFinite() && jitterPct in 0.0..30.0)
        require(maxCadenceSpm >= 0)
    }

    companion object {
        const val MAX_TARGET = 200_000L
        const val MAX_RATE = 180
    }
}
