package dev.arsignallocator.core

import android.os.SystemClock

fun interface MonotonicClock {
    fun nowNanos(): Long
}

object ElapsedRealtimeClock : MonotonicClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
