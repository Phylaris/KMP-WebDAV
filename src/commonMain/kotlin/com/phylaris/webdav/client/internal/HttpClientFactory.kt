package com.phylaris.webdav.client.internal

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Creates an [HttpClient] with the platform's default engine:
 * OkHttp on Android, Darwin (NSURLSession) on iOS, CIO on JVM,
 * Js (browser fetch) on JS/Wasm.
 * Consumers may pass their own client to [com.phylaris.webdav.client.WebDavClient]
 * for full control over the engine and plugins.
 */
internal expect fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient
