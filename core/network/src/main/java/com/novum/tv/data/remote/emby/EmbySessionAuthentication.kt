package com.novum.tv.data.remote.emby

import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Capture once per Call, while checking the captured origin on every redirect hop. */
fun OkHttpClient.Builder.addEmbySessionAuthentication(
    sessionHolder: SessionHolder,
    includeClientDescription: Boolean = false,
): OkHttpClient.Builder =
    addInterceptor { chain ->
        val request = chain.request()
        val session = request.tag(SessionSnapshot::class.java) ?: sessionHolder.capture()
        val authentication = CallAuthentication(request.url, session.tokenForUrl(request.url.toString()))
        val builder = request.newBuilder()
            .removeHeader(TOKEN_HEADER)
            .tag(CallAuthentication::class.java, authentication)
        if (includeClientDescription) {
            builder.header("X-Emby-Authorization", session.authorizationHeader())
        }
        chain.proceed(builder.build())
    }.addNetworkInterceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder().removeHeader(TOKEN_HEADER)
        request.tag(CallAuthentication::class.java)?.tokenForUrl(request.url)?.let { token ->
            builder.header(TOKEN_HEADER, token)
        }
        chain.proceed(builder.build())
    }

private const val TOKEN_HEADER = "X-Emby-Token"

private class CallAuthentication(private val origin: HttpUrl, private val token: String?) {
    fun tokenForUrl(url: HttpUrl): String? = token.takeIf {
        origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port
    }
}
