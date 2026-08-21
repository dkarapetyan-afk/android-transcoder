package com.androidcompress.app.util

/**
 * Values for the encode foreground notification, including Android 16 Live Update
 * progress (status-bar chip + [NotificationCompat.ProgressStyle] bar).
 *
 * Progress is tracked in 0..100 units per queue slot so a 3-job queue is 0..300.
 */
data class EncodeLiveUpdate(
    val percent: Int,
    val progress: Int,
    val progressMax: Int,
    val indeterminate: Boolean,
    val chipText: String?,
    val twoPass: Boolean,
    val passSplitAt: Int?,
    val segmentCount: Int,
) {
    companion object {
        const val CHIP_MAX_CHARS = 7
        const val UNIT = 100

        fun create(
            percent: Int,
            queueIndex: Int = 1,
            queueTotal: Int = 1,
            twoPass: Boolean = false,
        ): EncodeLiveUpdate {
            val pct = percent.coerceIn(0, 100)
            val jobs = queueTotal.coerceAtLeast(1)
            val index = queueIndex.coerceIn(1, jobs)
            val max = jobs * UNIT
            val progress = ((index - 1) * UNIT + pct).coerceIn(0, max)
            val indeterminate = progress <= 0
            val chip = "$pct%".takeIf { !indeterminate && it.length <= CHIP_MAX_CHARS }
            val split = if (twoPass) (index - 1) * UNIT + UNIT / 2 else null
            return EncodeLiveUpdate(
                percent = pct,
                progress = progress,
                progressMax = max,
                indeterminate = indeterminate,
                chipText = chip,
                twoPass = twoPass,
                passSplitAt = split,
                segmentCount = jobs,
            )
        }
    }
}
