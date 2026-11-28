package com.novum.tv.data.remote.emby

import java.io.IOException
import okhttp3.OkHttpClient

/** Enforce the app's HTTP consent policy for every network hop, including redirects. */
fun OkHttpClient.Builder.addCleartextOriginGuard(
    sessionHolder: SessionHolder,
): OkHttpClient.Builder = addNetworkInterceptor { chain ->
    val request = chain.request()
    if (!sessionHolder.isCleartextAllowed(request.url.toString())) {
        throw IOException("Cleartext HTTP origin is not authorized")
    }
    chain.proceed(request)
}
