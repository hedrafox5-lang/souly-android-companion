package com.souly.parental.shield.data.remote

import android.content.Context
import com.souly.parental.shield.data.prefs.SecureTokenStore
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object SoulyApiClient {

    @Volatile
    private var sharedOkHttpClient: OkHttpClient? = null

    @Volatile
    private var cachedRetrofitService: SoulyAndroidApiService? = null

    @Volatile
    private var cachedBaseUrl: String? = null

    private fun getOrCreateOkHttpClient(appContext: Context): OkHttpClient {
        return sharedOkHttpClient ?: synchronized(this) {
            sharedOkHttpClient ?: buildOkHttpClient(appContext).also { sharedOkHttpClient = it }
        }
    }

    private fun buildOkHttpClient(appContext: Context): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val tokenStore = SecureTokenStore(appContext)
            val original = chain.request()
            val token = tokenStore.getSanctumToken() ?: tokenStore.getDeviceUuid() ?: ""
            val builder = original.newBuilder()
                .header("Accept", "application/json")

            val bodyType = original.body?.contentType()?.type
            if (bodyType != "multipart") {
                builder.header("Content-Type", "application/json")
            }

            if (token.isNotBlank()) {
                builder.header("Authorization", "Bearer $token")
                builder.header("X-Souly-Device-Token", token)
            }

            val response = chain.proceed(builder.build())
            if (response.code == 401 && !original.url.encodedPath.endsWith("pair-device")) {
                tokenStore.clearPairingSession()
            }
            response
        }

        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor(authInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun create(context: Context): SoulyAndroidApiService {
        val appCtx = context.applicationContext
        val tokenStore = SecureTokenStore(appCtx)
        val baseUrl = tokenStore.getHubBaseUrl()

        val existingService = cachedRetrofitService
        if (existingService != null && cachedBaseUrl == baseUrl) {
            return existingService
        }

        return synchronized(this) {
            val currentBase = tokenStore.getHubBaseUrl()
            if (cachedRetrofitService != null && cachedBaseUrl == currentBase) {
                cachedRetrofitService!!
            } else {
                val service = Retrofit.Builder()
                    .baseUrl(currentBase)
                    .client(getOrCreateOkHttpClient(appCtx))
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(SoulyAndroidApiService::class.java)
                cachedBaseUrl = currentBase
                cachedRetrofitService = service
                service
            }
        }
    }
}
