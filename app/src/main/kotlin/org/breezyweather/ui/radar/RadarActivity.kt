/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.ui.radar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import breezyweather.data.location.LocationRepository
import breezyweather.domain.location.model.Location
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.setSystemBarStyle
import org.breezyweather.domain.location.model.getPlace
import org.breezyweather.domain.settings.SettingsManager
import org.breezyweather.unit.temperature.TemperatureUnit
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Weather maps: rain & snow radar (RainViewer), wind, fog, river stages, lightning,
 * hurricanes (NHC), tornado / severe warnings (NWS) and eclipse paths.
 *
 * The map itself is GeauxWeather's Leaflet map (assets/radar, GPL-3.0-or-later), shown in a
 * WebView. Its files are served from the reserved https://appassets.androidplatform.net origin,
 * and NOAA / NWS feeds, which send no CORS headers, are fetched natively through [RadarBridge].
 */
@AndroidEntryPoint
class RadarActivity : BreezyActivity() {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var locationRepository: LocationRepository

    private lateinit var webView: WebView
    private val ioExecutor = Executors.newFixedThreadPool(3)
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The map page is always dark.
        window.setSystemBarStyle(false)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0b0f14"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = settings.userAgentString + " BreezyWeather"
            webViewClient = RadarWebViewClient(this@RadarActivity)
            addJavascriptInterface(RadarBridge(), "BreezyRadar")
        }
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )

        val lat = intent.getDoubleExtra(KEY_LATITUDE, Double.NaN)
        val lon = intent.getDoubleExtra(KEY_LONGITUDE, Double.NaN)
        if (!lat.isNaN() && !lon.isNaN()) {
            loadMap(lat, lon, intent.getStringExtra(KEY_NAME))
        } else {
            // Opened without a location (e.g. from a launcher shortcut): use the first saved location.
            lifecycleScope.launch {
                val location = withContext(Dispatchers.IO) {
                    locationRepository.getFirstLocation(withParameters = false)
                }
                if (location != null && location.hasCoordinates) {
                    loadMap(location.latitude, location.longitude, location.getPlace(this@RadarActivity))
                } else {
                    loadMap(null, null, null)
                }
            }
        }
    }

    private fun loadMap(lat: Double?, lon: Double?, name: String?) {
        val metric = SettingsManager.getInstance(this).getTemperatureUnit(this) != TemperatureUnit.FAHRENHEIT
        val url = Uri.parse("$ASSET_ORIGIN/radar/index.html").buildUpon().apply {
            if (lat != null && lon != null) {
                appendQueryParameter("lat", lat.toString())
                appendQueryParameter("lon", lon.toString())
            }
            if (!name.isNullOrEmpty()) appendQueryParameter("name", name)
            appendQueryParameter("units", if (metric) "metric" else "imperial")
            intent.getStringExtra(KEY_LAYER)?.let { appendQueryParameter("layer", it) }
        }.build()
        webView.loadUrl(url.toString())
        pageLoaded = true
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (pageLoaded) webView.evaluateJavascript("window.__breezyRadarResume && __breezyRadarResume()", null)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        webView.removeJavascriptInterface("BreezyRadar")
        webView.destroy()
        super.onDestroy()
    }

    /** Native GET for feeds that can't be fetched from the page because of CORS. */
    private inner class RadarBridge {
        @JavascriptInterface
        fun httpGet(id: Int, url: String) {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || !isAllowedHost(uri.host)) {
                resolve(id, 0, null, "Host not allowed")
                return
            }
            ioExecutor.execute {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/geo+json, application/json, */*")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val bytes = response.body.bytes()
                        resolve(id, response.code, Base64.encodeToString(bytes, Base64.NO_WRAP), null)
                    }
                } catch (e: IOException) {
                    resolve(id, 0, null, e.message ?: "Network error")
                } catch (e: IllegalArgumentException) {
                    resolve(id, 0, null, e.message ?: "Bad URL")
                }
            }
        }
    }

    private fun resolve(id: Int, status: Int, bodyBase64: String?, error: String?) {
        val js = "window.__breezyNetResolve && __breezyNetResolve(" +
            id + "," + status + "," +
            JSONObject.quote(bodyBase64 ?: "") + "," +
            (if (error != null) JSONObject.quote(error) else "null") + ")"
        runOnUiThread {
            if (!isDestroyed) webView.evaluateJavascript(js, null)
        }
    }

    /** Serves assets/radar at https://appassets.androidplatform.net/radar/ and keeps links in check. */
    private class RadarWebViewClient(private val context: Context) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme != "https" || uri.host != ASSET_HOST) return null
            val path = uri.path?.trimStart('/') ?: return notFound()
            if (!path.startsWith("radar/") || path.contains("..")) return notFound()
            return try {
                WebResourceResponse(mimeType(path), "utf-8", context.assets.open(path))
            } catch (e: IOException) {
                notFound()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.host == ASSET_HOST) return false
            // Attribution and popup links open in the browser, not inside the map.
            if (uri.scheme == "https" || uri.scheme == "http") {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (ignored: Exception) {
                }
            }
            return true
        }

        private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null)

        private fun mimeType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_NAME = "name"

        /** Optional starting layer: radar, wind, fog, rivers, lightning, hurricane, tornado, eclipse. */
        const val KEY_LAYER = "layer"

        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ASSET_ORIGIN = "https://$ASSET_HOST"

        private val ALLOWED_HOST = Regex(
            "^(?:[\\w-]+\\.)*(?:noaa\\.gov|weather\\.gov|rainviewer\\.com|open-meteo\\.com)$",
            RegexOption.IGNORE_CASE
        )

        private fun isAllowedHost(host: String?) = host != null && ALLOWED_HOST.matches(host)

        private val Location.hasCoordinates: Boolean
            get() = latitude != 0.0 || longitude != 0.0

        fun start(context: Context, location: Location?) {
            context.startActivity(
                Intent(context, RadarActivity::class.java).apply {
                    if (location != null && location.hasCoordinates) {
                        putExtra(KEY_LATITUDE, location.latitude)
                        putExtra(KEY_LONGITUDE, location.longitude)
                        putExtra(KEY_NAME, location.getPlace(context))
                    }
                }
            )
        }
    }
}
