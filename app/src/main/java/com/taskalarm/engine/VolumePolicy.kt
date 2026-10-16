package com.taskalarm.engine

internal object VolumePolicy {
  fun guardedLevel(initial: Int, maximum: Int): Int =
    if (maximum <= 0) 0 else (if (initial > 0) initial else (maximum + 1) / 2).coerceIn(1, maximum)
  fun mayMute(allowed: Boolean, solving: Boolean) = allowed && solving
}
