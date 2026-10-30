package com.tiltbox.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cuaca nyata: lokasi (perangkat -> IP -> manual) + prakiraan dari Open-Meteo.
 * Semua jaringan dilakukan di sini (bukan di WebView) agar halaman web tidak perlu akses internet.
 */
object WeatherService {
    private const val TAG = "TiltboxWeather"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val GEO_URL = "https://api.bigdatacloud.net/data/reverse-geocode-client"
    private const val LOCATION_MAX_AGE_MS = 6L * 60 * 60 * 1000
    private const val MAX_RESPONSE_CHARS = 1_000_000

    private class WeatherException(val code: String, cause: Throwable? = null) : Exception(code, cause)

    private class Place(val name: String, val lat: Double, val lon: Double, val source: String)

    fun hasLocationPermission(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Panggil dari background thread. Mengembalikan JSON:
     * {ok:true, place:{name,lat,lon,source}, locPermission, fetchedAt, data:<respons Open-Meteo>}
     * atau {ok:false, error:"network"|"nolocation"|"badlocation"}.
     * mode "manual": pakai lat/lon/name dari pemanggil; selain itu lokasi otomatis.
     */
    fun load(ctx: Context, mode: String, lat: Double, lon: Double, name: String): String {
        return try {
            val permission = hasLocationPermission(ctx)
            val place = if (mode == "manual") {
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw WeatherException("badlocation")
                Place(name.trim().ifEmpty { formatCoords(lat, lon) }, lat, lon, "manual")
            } else {
                resolveAuto(ctx, permission)
            }
            val resp = httpGetWithHeaders(forecastUrl(place.lat, place.lon))
            val forecast = JSONObject(resp.body)
            val serverDateMs = resp.serverDateMs
            val driftMs = if (serverDateMs > 0) serverDateMs - System.currentTimeMillis() else 0L

            JSONObject()
                .put("ok", true)
                .put("place", JSONObject()
                    .put("name", place.name)
                    .put("lat", place.lat)
                    .put("lon", place.lon)
                    .put("source", place.source))
                .put("locPermission", permission)
                .put("fetchedAt", System.currentTimeMillis())
                .put("serverTimeMs", if (serverDateMs > 0) serverDateMs else System.currentTimeMillis())
                .put("timeDriftMs", driftMs)
                .put("data", forecast)
                .toString()
        } catch (e: WeatherException) {
            errorJson(e.code)
        } catch (t: Throwable) {
            Log.w(TAG, "Gagal memuat cuaca", t)
            errorJson("network")
        }
    }

    private fun errorJson(code: String): String =
        JSONObject().put("ok", false).put("error", code).toString()

    private fun resolveAuto(ctx: Context, permission: Boolean): Place {
        if (permission) {
            val loc = deviceLocation(ctx)
            if (loc != null) {
                val name = reverseGeocode(loc.latitude, loc.longitude) ?: "Lokasi perangkat"
                return Place(name, loc.latitude, loc.longitude, "device")
            }
        }
        // Cadangan: perkiraan dari alamat IP (tanpa izin lokasi)
        val body = try {
            httpGet("$GEO_URL?localityLanguage=id")
        } catch (e: IOException) {
            throw WeatherException("network", e)
        }
        try {
            val j = JSONObject(body)
            if (j.has("latitude") && j.has("longitude")) {
                val la = j.getDouble("latitude")
                val lo = j.getDouble("longitude")
                return Place(pickName(j) ?: formatCoords(la, lo), la, lo, "ip")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Respons lokasi IP tidak valid", e)
        }
        throw WeatherException("nolocation")
    }

    // TV jarang punya GPS; cukup NETWORK/fused dengan izin lokasi perkiraan (COARSE).
    @SuppressLint("MissingPermission")
    private fun deviceLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, "fused")
        var best: Location? = null
        for (p in providers) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (e: Exception) {
                // provider tidak ada / izin tidak cukup: coba berikutnya
            }
        }
        if (best != null && System.currentTimeMillis() - best.time < LOCATION_MAX_AGE_MS) return best

        val enabled = providers.firstOrNull { p -> try { lm.isProviderEnabled(p) } catch (e: Exception) { false } }
        if (enabled != null) {
            val fresh = requestSingle(lm, enabled)
            if (fresh != null) return fresh
        }
        return best
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun requestSingle(lm: LocationManager, provider: String): Location? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Location>(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                holder[0] = location
                latch.countDown()
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            latch.await(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Update lokasi tunggal gagal", e)
        } finally {
            try { lm.removeUpdates(listener) } catch (e: Exception) { }
        }
        return holder[0]
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? = try {
        val url = String.format(Locale.US, "%s?latitude=%.4f&longitude=%.4f&localityLanguage=id", GEO_URL, lat, lon)
        pickName(JSONObject(httpGet(url)))
    } catch (e: Exception) {
        null
    }

    private fun pickName(j: JSONObject): String? {
        for (k in arrayOf("city", "locality", "principalSubdivision")) {
            val v = j.optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun formatCoords(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.2f, %.2f", lat, lon)

    private fun forecastUrl(lat: Double, lon: Double): String = String.format(
        Locale.US,
        "%s?latitude=%.4f&longitude=%.4f" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max" +
            "&timezone=auto&forecast_days=5&wind_speed_unit=kmh",
        FORECAST_URL, lat, lon
    )

    private class HttpResult(val body: String, val serverDateMs: Long)

    private fun httpGet(url: String): String = httpGetWithHeaders(url).body

    private fun httpGetWithHeaders(url: String): HttpResult {
        if (!url.startsWith("https://")) throw WeatherException("network")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "TiltboxLauncher/1.7")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val serverDateMs = conn.date
            val sb = StringBuilder()
            conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > MAX_RESPONSE_CHARS) throw IOException("Respons terlalu besar")
                }
            }
            return HttpResult(sb.toString(), serverDateMs)
        } finally {
            conn.disconnect()
        }
    }
}