package com.jono.nowwidget.core

/** "<1m", "45m", "2h", "1h 5m": how long something took, everywhere in the app and in the notes. */
fun formatDuration(ms: Long): String {
    val m = ms / 60_000L
    return when {
        m < 1L -> "<1m"
        m < 60L -> "${m}m"
        m % 60L == 0L -> "${m / 60L}h"
        else -> "${m / 60L}h ${m % 60L}m"
    }
}
