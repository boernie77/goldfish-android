package com.goldfish.android.data.api

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersistentCookieJar @Inject constructor(
    @ApplicationContext private val context: Context
) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("goldfish_cookies", Context.MODE_PRIVATE)

    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    init {
        // Restore cookies from prefs on startup
        prefs.all.forEach { (key, value) ->
            if (value is String) {
                // We store session cookie directly
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        cookieStore[host] = cookies
        val editor = prefs.edit()
        cookies.forEach { cookie ->
            editor.putString("cookie_${cookie.name}_${host}", cookie.toString())
        }
        editor.apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        // Return in-memory cookies first
        cookieStore[host]?.let { return it }

        // Restore from prefs
        val cookies = mutableListOf<Cookie>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("cookie_") && key.endsWith("_$host") && value is String) {
                Cookie.parse(url, value)?.let { cookies.add(it) }
            }
        }
        if (cookies.isNotEmpty()) {
            cookieStore[host] = cookies
        }
        return cookies
    }

    fun clearAll() {
        cookieStore.clear()
        prefs.edit().clear().apply()
    }

    fun hasSession(): Boolean {
        return prefs.all.any { (key, _) -> key.startsWith("cookie_") }
    }
}

@Singleton
class ApiClientProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieJar: PersistentCookieJar
) {
    private var _baseUrl: String = ""
    private var _cacheSize: Long = 200L * 1024 * 1024 // 200MB default

    private var _okHttpClient: OkHttpClient? = null
    private var _retrofit: Retrofit? = null
    private var _api: GoldfishApi? = null

    // Wird true sobald configure() mit einer validen URL gelaufen ist.
    // DownloadRepository observed das, um den Backfill erst zu starten,
    // wenn die API tatsaechlich angesprochen werden kann.
    private val _configured = kotlinx.coroutines.flow.MutableStateFlow(false)
    val configured: kotlinx.coroutines.flow.StateFlow<Boolean> = _configured

    val okHttpClient: OkHttpClient
        get() = _okHttpClient ?: buildOkHttpClient().also { _okHttpClient = it }

    val api: GoldfishApi
        get() = _api ?: buildApi().also { _api = it }

    fun configure(baseUrl: String, cacheSize: Long) {
        val trimmed = baseUrl.trim().trimEnd('/')
        // Leere oder offensichtlich invalide URL ignorieren — sonst baut
        // Retrofit baseUrl="/" und wirft "Expected URL scheme http or https".
        // Stattdessen bleibt der ApiClient im "uninitialized"-Zustand, bis
        // der User eine valide URL in Settings einträgt.
        if (trimmed.isBlank() || !(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            return
        }
        val newUrl = "$trimmed/"
        val urlChanged = newUrl != _baseUrl
        val cacheChanged = cacheSize != _cacheSize
        if (urlChanged || cacheChanged) {
            _baseUrl = newUrl
            _cacheSize = cacheSize
            _okHttpClient = null
            _retrofit = null
            _api = null
        }
        _configured.value = true
    }

    /** True wenn eine valide Server-URL gesetzt ist. */
    fun isConfigured(): Boolean = _baseUrl.startsWith("http://") || _baseUrl.startsWith("https://")

    private fun buildOkHttpClient(): OkHttpClient {
        val cacheDir = java.io.File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, _cacheSize)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .cache(cache)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun buildApi(): GoldfishApi {
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(_baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        _retrofit = retrofit
        return retrofit.create(GoldfishApi::class.java)
    }

    fun getBaseUrl(): String = _baseUrl
}
