package com.sylvandale.webdav.client.internal

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

// The Ktor Js engine also backs the Wasm target: ktor-client-js publishes a
// wasm-js variant of the same io.ktor.client.engine.js.Js factory.
internal actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) { config() }
