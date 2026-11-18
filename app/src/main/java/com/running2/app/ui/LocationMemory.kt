package com.running2.app.ui

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.maps.model.LatLng

/**
 * The last place the map showed the user, kept across launches so the first frame of every map
 * starts there instead of at Google's default (0, 0) world view. It is only a camera hint: it is
 * never drawn as the current position and never enters the run record.
 */
object LocationMemory {
    private const val PREFS = "map_camera"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val PERSIST_INTERVAL_MS = 30_000L

    @Volatile private var cached: LatLng? = null
    @Volatile private var loaded = false
    @Volatile private var lastPersistMs = 0L

    /** Synchronous so the map can be created with the right camera; SharedPreferences is cached in memory. */
    fun last(context: Context): LatLng? {
        if (!loaded) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_LAT) && prefs.contains(KEY_LNG)) {
                val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0))
                val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LNG, 0))
                if (lat in -90.0..90.0 && lng in -180.0..180.0) cached = LatLng(lat, lng)
            }
            loaded = true
        }
        return cached
    }

    /** Called for every live location; disk writes are throttled, [flush] forces one. */
    fun update(context: Context, latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        cached = LatLng(latitude, longitude)
        loaded = true
        val now = SystemClock.elapsedRealtime()
        if (lastPersistMs == 0L || now - lastPersistMs >= PERSIST_INTERVAL_MS) flush(context)
    }

    fun flush(context: Context) {
        val value = cached ?: return
        lastPersistMs = SystemClock.elapsedRealtime()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(value.latitude))
            .putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(value.longitude))
            .apply()
    }
}
