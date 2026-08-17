package com.phylaris.webdav.client

import com.phylaris.webdav.client.internal.ChannelReadContent
import com.phylaris.webdav.client.internal.MultiStatusParser
import com.phylaris.webdav.client.internal.MultiStatusResponse
import com.phylaris.webdav.client.internal.ProgressListener
import com.phylaris.webdav.client.internal.ProgressReadChannelContent
import com.phylaris.webdav.client.internal.XmlBody
import com.phylaris.webdav.client.internal.copyWithProgress
import com.phylaris.webdav.client.internal.createHttpClient
import com.phylaris.webdav.client.internal.readBytesWithProgress
import com.phylaris.webdav.client.model.LockInfo
import com.phylaris.webdav.client.model.LockScope
import com.phylaris.webdav.client.model.LockType
import com.phylaris.webdav.client.model.PropValue
import com.phylaris.webdav.client.model.PropertyName
import com.phylaris.webdav.client.model.QuotaInfo
import com.phylaris.webdav.client.model.WebDavFile
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth as KtorAuth
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodedPath
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.readAvailable
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** WebDAV methods not covered by Ktor's [HttpMethod] constants. */
val PROPFIND = HttpMethod("PROPFIND")
val PROPPATCH = HttpMethod("PROPPATCH")
val MKCOL = HttpMethod("MKCOL")
val MOVE = HttpMethod("MOVE")
val COPY = HttpMethod("COPY")
val LOCK = HttpMethod("LOCK")
val UNLOCK = HttpMethod("UNLOCK")

private const val MAX_ERROR_BODY = 512

// WebDAV-specific headers not exposed by Ktor's HttpHeaders.
private const val DEPTH = "Depth"
private const val DESTINATION = "Destination"
private const val OVERWRITE = "Overwrite"
private const val LOCK_TOKEN = "Lock-Token"
private const val IF_HEADER = "If"
private const val IF_NONE_MATCH = "If-None-Match"
private const val TIMEOUT = "Timeout"
private const val CONTENT_RANGE = "Content-Range"
private const val DAV = "DAV"

/**
 * A WebDAV client (RFC 4918) implemented in common Kotlin for Android, iOS and JVM.
 *
 * All [path] arguments are logical, URL-encoded-when-needed paths relative to the
 * client's base URL, e.g. `"docs/report 2026.txt"` (no leading slash). The empty
 * string refers to the root collection.
 *
 * The client does **not** follow redirects automatically; 3xx responses are followed
 * manually so that request bodies and WebDAV semantics (PROPFIND, PUT, ...) survive
 * redirects. If you construct the client with your own [HttpClient], make sure
 * `followRedirects` is disabled on it.
 */
class WebDavClient(
    val httpClient: HttpClient,
    baseUrl: String,
    private val config: WebDavClientConfig = WebDavClientConfig(),
) {

    /** The normalized base URL (path guaranteed to end with '/'). */
    val baseUrl: Url = Url(baseUrl.trimEnd('/') + "/")

    companion object {
        /** Properties commonly requested when listing resources. */
        val COMMON_PROPERTIES: List<PropertyName> = listOf(
            PropertyName.DISPLAYNAME,
            PropertyName.RESOURCETYPE,
            PropertyName.GETCONTENTLENGTH,
            PropertyName.GETLASTMODIFIED,
            PropertyName.GETETAG,
            PropertyName.GETCONTENTTYPE,
        )

        /**
         * Creates a [WebDavClient] with the platform's default HTTP engine
         * (OkHttp on Android, Darwin on iOS, CIO on JVM, Js on browser JS/Wasm)
         * and the given [auth] scheme.
         *
         * @param httpClientConfig additional Ktor client configuration, applied last
         */
        fun create(
            baseUrl: String,
            auth: Auth = Auth.None,
            config: WebDavClientConfig = WebDavClientConfig(),
            httpClientConfig: HttpClientConfig<*>.() -> Unit = {},
        ): WebDavClient {
            val client = createHttpClient {
                followRedirects = false
                expectSuccess = false
                // Ktor 3 sends no default User-Agent; some servers (e.g. fnos)
                // reject UA-less requests with 401 Unauthorized.
                defaultRequest {
                    header(HttpHeaders.UserAgent, "KMP-WebDAV/1.0")
                }
                if (auth !is Auth.None) {
                    install(KtorAuth) {
                        when (auth) {
                            is Auth.Basic -> basic {
                                credentials { BasicAuthCredentials(auth.username, auth.password) }
                                sendWithoutRequest { true }
                            }
                            is Auth.Digest -> digest {
                                credentials { DigestAuthCredentials(auth.username, auth.password) }
                            }
                            is Auth.Bearer -> bearer {
                                loadTokens { BearerTokens(auth.tokenProvider(), "") }
                            }
                            Auth.None -> Unit
                        }
                    }
                }
                httpClientConfig()
            }
            return WebDavClient(client, baseUrl, config)
        }
    }

    // ------------------------------------------------------------------ listing

    /**
     * Lists the members of the collection at [path].
     *
     * @param depth Depth of the PROPFIND request; [Depth.ONE] lists direct children,
     *   [Depth.ZERO] returns only the collection itself, [Depth.INFINITY] the whole
     *   subtree (may be rejected by the server).
     */
    suspend fun list(
        path: String = "",
        depth: Depth = Depth.ONE,
        properties: List<PropertyName> = COMMON_PROPERTIES,
    ): List<WebDavFile> {
        val body = if (properties.isEmpty()) XmlBody.propfindAllProp() else XmlBody.propfindProps(properties)
        val response = execute(
            PROPFIND, path,
            headers = buildHeaders {
                append(DEPTH, depth.headerValue)
                append(HttpHeaders.ContentType, "application/xml; charset=utf-8")
            },
            body = body.toTextContent(),
        )
        ensureSuccess(response, PROPFIND, path)
        val multiStatus = MultiStatusParser.parseMultiStatus(response.bodyAsText())
        return multiStatus.responses.mapNotNull { it.toWebDavFile() }
            .filter { it.path.isNotEmpty() }
            // A Depth-1 PROPFIND also returns the target collection itself;
            // list() is documented to return only its members.
            .filter { it.path != path }
    }

    /** Fetches the properties of a single resource (PROPFIND with Depth: 0). */
    suspend fun getProperties(
        path: String = "",
        properties: List<PropertyName> = COMMON_PROPERTIES,
    ): WebDavFile? {
        val body = if (properties.isEmpty()) XmlBody.propfindAllProp() else XmlBody.propfindProps(properties)
        val response = execute(
            PROPFIND, path,
            headers = buildHeaders {
                append(DEPTH, Depth.ZERO.headerValue)
                append(HttpHeaders.ContentType, "application/xml; charset=utf-8")
            },
            body = body.toTextContent(),
        )
        ensureSuccess(response, PROPFIND, path)
        val multiStatus = MultiStatusParser.parseMultiStatus(response.bodyAsText())
        return multiStatus.responses.firstNotNullOfOrNull { it.toWebDavFile() }
    }

    /** Lists all resources in the subtree at [path], with bounded concurrency. */
    suspend fun listRecursive(
        path: String = "",
        properties: List<PropertyName> = COMMON_PROPERTIES,
        concurrency: Int = 4,
    ): List<WebDavFile> {
        require(concurrency >= 1) { "concurrency must be >= 1" }
        val result = mutableListOf<WebDavFile>()
        val pending = ArrayDeque<String>()
        pending.addLast(path)
        val semaphore = Semaphore(concurrency)
        while (pending.isNotEmpty()) {
            val level = pending.toList()
            pending.clear()
            // Each worker returns its own findings; the caller merges them, so no
            // shared mutable state is touched from the worker coroutines.
            val levelResults: List<Pair<List<WebDavFile>, List<String>>> = coroutineScope {
                level.map { dir ->
                    async {
                        semaphore.withPermit {
                            val children = list(dir, Depth.ONE, properties)
                            children to children.filter { it.isDirectory }.map { it.path }
                        }
                    }
                }.awaitAll()
            }
            for ((files, dirs) in levelResults) {
                result.addAll(files)
                pending.addAll(dirs)
            }
        }
        return result
    }

    // ------------------------------------------------------------------ download

    /**
     * Downloads the resource at [path] and returns the raw [HttpResponse].
     * Consume or cancel the response body (`bodyAsChannel()` / `bodyAsText()`);
     * the caller is responsible for closing it.
     *
     * @param range optional byte range to download (HTTP Range request, for resume)
     * @param ifNoneMatch optional ETag; the server answers 304 Not Modified when the
     *   resource matches (the response is returned as-is, not translated to an error)
     */
    suspend fun download(
        path: String,
        range: LongRange? = null,
        ifNoneMatch: String? = null,
    ): HttpResponse {
        val headers = buildHeaders {
            if (range != null) append(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
            if (ifNoneMatch != null) append(HttpHeaders.IfNoneMatch, ifNoneMatch)
        }
        val response = execute(HttpMethod.Get, path, headers)
        // 304 is a valid conditional-response outcome, not an error; the convenience
        // downloadBytes/downloadToChannel translate it into NotModifiedException.
        if (response.status != HttpStatusCode.NotModified) {
            ensureSuccess(response, HttpMethod.Get, path)
        }
        return response
    }

    /** Downloads the resource at [path] fully into memory. Prefer [downloadToChannel] for large files. */
    suspend fun downloadBytes(
        path: String,
        range: LongRange? = null,
        ifNoneMatch: String? = null,
        onProgress: ProgressListener? = null,
    ): ByteArray {
        val response = download(path, range, ifNoneMatch)
        if (response.status == HttpStatusCode.NotModified) {
            throw NotModifiedException(HttpMethod.Get, buildUrl(path).toString())
        }
        val channel = response.bodyAsChannel()
        val expected = response.contentLength()
        return if (onProgress != null) {
            channel.readBytesWithProgress(expected, onProgress)
        } else {
            channel.readAll()
        }
    }

    /** Downloads the resource at [path] into [sink], reporting progress when [onProgress] is given. */
    suspend fun downloadToChannel(
        path: String,
        sink: ByteWriteChannel,
        range: LongRange? = null,
        ifNoneMatch: String? = null,
        onProgress: ProgressListener? = null,
    ) {
        val response = download(path, range, ifNoneMatch)
        if (response.status == HttpStatusCode.NotModified) {
            throw NotModifiedException(HttpMethod.Get, buildUrl(path).toString())
        }
        val channel = response.bodyAsChannel()
        val expected = response.contentLength()
        if (onProgress != null) {
            channel.copyWithProgress(sink, expected, onProgress)
        } else {
            channel.copyAndClose(sink)
        }
    }

    private fun HttpResponse.contentLength(): Long? =
        headers[HttpHeaders.ContentLength]?.toLongOrNull()

    // ------------------------------------------------------------------ upload

    /**
     * Uploads a stream as the content of [path] (PUT).
     *
     * @param contentLength expected size; when null the body is sent without
     *   Content-Length (chunked where supported).
     * @param overwrite if false, the request carries `If-None-Match: *` and fails
     *   with [PreconditionFailedException] when the resource already exists.
     * @param lockToken when the resource is locked, the token obtained from [lock];
     *   the request then carries `If: (<token>)` (RFC 4918 §7.3, required by
     *   servers like Apache mod_dav to avoid 423 on locked resources).
     * @param ifMatch optional ETag; the request carries `If-Match` and fails with
     *   [PreconditionFailedException] when the resource changed (optimistic concurrency).
     */
    suspend fun uploadFromChannel(
        path: String,
        channel: ByteReadChannel,
        contentLength: Long? = null,
        contentType: ContentType = ContentType.Application.OctetStream,
        overwrite: Boolean = true,
        lockToken: String? = null,
        ifMatch: String? = null,
        onProgress: ProgressListener? = null,
    ) {
        val body = if (onProgress != null) {
            ProgressReadChannelContent(channel, contentLength, contentType, onProgress)
        } else {
            ChannelReadContent(channel, contentLength, contentType)
        }
        upload(path, body, overwrite, lockToken, ifMatch)
    }

    /** Uploads [bytes] as the content of [path] (PUT). See [upload] for [lockToken]/[ifMatch]. */
    suspend fun uploadBytes(
        path: String,
        bytes: ByteArray,
        contentType: ContentType = ContentType.Application.OctetStream,
        overwrite: Boolean = true,
        lockToken: String? = null,
        ifMatch: String? = null,
    ) {
        upload(path, ByteArrayContent(bytes, contentType), overwrite, lockToken, ifMatch)
    }

    /** Uploads a raw [OutgoingContent] body to [path] (PUT). */
    suspend fun upload(
        path: String,
        content: OutgoingContent,
        overwrite: Boolean = true,
        lockToken: String? = null,
        ifMatch: String? = null,
    ) {
        require(ifMatch == null || overwrite) { "ifMatch cannot be combined with overwrite=false" }
        val headers = buildHeaders {
            if (!overwrite) append(IF_NONE_MATCH, "*")
            if (lockToken != null) append(IF_HEADER, "<$lockToken>")
            if (ifMatch != null) append(HttpHeaders.IfMatch, ifMatch)
        }
        val response = execute(HttpMethod.Put, path, headers, content)
        ensureSuccess(response, HttpMethod.Put, path)
    }

    /**
     * Resumes an interrupted upload by appending the remaining [channel] at [offset] bytes
     * (PUT with a `Content-Range` header).
     *
     * This is a non-standard extension supported by some servers (e.g. Apache mod_dav);
     * check the response: success is 200/201/204, and a 416 [HttpStatusException] means
     * the server does not accept the range (retry from scratch).
     *
     * @param offset the number of bytes already transferred successfully
     * @param totalLength the total size of the file being uploaded
     * @param lockToken when the resource is locked, the token obtained from [lock]
     */
    suspend fun uploadResume(
        path: String,
        channel: ByteReadChannel,
        offset: Long,
        totalLength: Long,
        contentType: ContentType = ContentType.Application.OctetStream,
        lockToken: String? = null,
        onProgress: ProgressListener? = null,
    ) {
        require(offset >= 0 && totalLength > offset) { "invalid offset $offset for total $totalLength" }
        val remaining = totalLength - offset
        val body = if (onProgress != null) {
            ProgressReadChannelContent(channel, remaining, contentType, onProgress)
        } else {
            ChannelReadContent(channel, remaining, contentType)
        }
        val headers = buildHeaders {
            append(CONTENT_RANGE, "bytes $offset-*/$totalLength")
            if (lockToken != null) append(IF_HEADER, "<$lockToken>")
        }
        val response = execute(HttpMethod.Put, path, headers, body)
        ensureSuccess(response, HttpMethod.Put, path)
    }

    // ------------------------------------------------------------------ mutations

    /** Creates a new collection (directory) at [path] (MKCOL). */
    suspend fun mkdir(path: String) {
        require(path.isNotBlank()) { "path must not be blank" }
        val response = execute(MKCOL, path)
        ensureSuccess(response, MKCOL, path)
    }

    /**
     * Deletes the resource at [path] (DELETE).
     *
     * @param lockToken when the resource is locked, the token obtained from [lock]
     * @param ifMatch optional ETag; the request carries `If-Match` and fails with
     *   [PreconditionFailedException] when the resource changed
     */
    suspend fun delete(path: String, lockToken: String? = null, ifMatch: String? = null) {
        val headers = buildHeaders {
            if (lockToken != null) append(IF_HEADER, "<$lockToken>")
            if (ifMatch != null) append(HttpHeaders.IfMatch, ifMatch)
        }
        val response = execute(HttpMethod.Delete, path, headers)
        ensureSuccess(response, HttpMethod.Delete, path)
    }

    /** Moves the resource at [source] to [destination] (MOVE). */
    suspend fun move(
        source: String,
        destination: String,
        overwrite: Boolean = true,
        lockToken: String? = null,
        ifMatch: String? = null,
    ) {
        val response = execute(
            MOVE, source,
            headers = destinationHeaders(destination, overwrite, lockToken, ifMatch),
        )
        ensureSuccess(response, MOVE, source)
    }

    /** Copies the resource at [source] to [destination] (COPY). */
    suspend fun copy(
        source: String,
        destination: String,
        overwrite: Boolean = true,
        depth: Depth = Depth.INFINITY,
        lockToken: String? = null,
        ifMatch: String? = null,
    ) {
        val response = execute(
            COPY, source,
            headers = buildHeaders {
                append(DESTINATION, buildUrl(destination).toString())
                append(OVERWRITE, if (overwrite) "T" else "F")
                append(DEPTH, depth.headerValue)
                if (lockToken != null) append(IF_HEADER, "<$lockToken>")
                if (ifMatch != null) append(HttpHeaders.IfMatch, ifMatch)
            },
        )
        ensureSuccess(response, COPY, source)
    }

    /** Sets and/or removes properties on the resource at [path] (PROPPATCH). */
    suspend fun setProperties(
        path: String,
        set: Map<PropertyName, String> = emptyMap(),
        remove: Set<PropertyName> = emptySet(),
        lockToken: String? = null,
    ) {
        require(set.isNotEmpty() || remove.isNotEmpty()) { "nothing to patch" }
        val response = execute(
            PROPPATCH, path,
            headers = buildHeaders {
                append(HttpHeaders.ContentType, "application/xml; charset=utf-8")
                if (lockToken != null) append(IF_HEADER, "<$lockToken>")
            },
            body = XmlBody.proppatch(set, remove).toTextContent(),
        )
        if (response.status == HttpStatusCode.MultiStatus) {
            val multiStatus = MultiStatusParser.parseMultiStatus(response.bodyAsText())
            val failed = multiStatus.responses
                .flatMap { it.propStats }
                .filterNot { it.isSuccess }
            if (failed.isNotEmpty()) {
                throw DavProtocolException(
                    "PROPPATCH on $path partially failed: " +
                        failed.joinToString { "${it.status}: ${it.props.keys.joinToString()}" }
                )
            }
        } else {
            ensureSuccess(response, PROPPATCH, path)
        }
    }

    // ------------------------------------------------------------------ locking

    /**
     * Locks the resource at [path] with a write lock (LOCK).
     *
     * @param owner optional owner information stored in the lock
     * @param timeout requested lock lifetime, e.g. [Duration.seconds] (server may grant less)
     * @param scope the lock scope: exclusive (default) or shared (RFC 4918, section 6.1)
     */
    suspend fun lock(
        path: String,
        owner: String? = null,
        timeout: Duration? = null,
        scope: LockScope = LockScope.EXCLUSIVE,
    ): LockInfo {
        val headers = buildHeaders {
            append(DEPTH, Depth.ZERO.headerValue)
            append(HttpHeaders.ContentType, "application/xml; charset=utf-8")
            if (timeout != null) append(TIMEOUT, "Second-${timeout.inWholeSeconds}")
        }
        val response = execute(
            LOCK, path,
            headers = headers,
            body = XmlBody.lock(owner, scope).toTextContent(),
        )
        ensureSuccess(response, LOCK, path)

        // Prefer the standard Lock-Token response header, fall back to the body.
        val headerToken = response.headers[LOCK_TOKEN]?.trim()?.removeSurrounding("<", ">")
        if (headerToken != null) {
            return LockInfo(token = headerToken, scope = scope, type = LockType.WRITE)
        }
        val body = response.bodyAsText()
        val info = MultiStatusParser.parseLockResponse(body)
        return info ?: throw DavProtocolException("LOCK on $path succeeded but no lock token was returned")
    }

    /**
     * Refreshes an existing lock on [path], extending its lifetime (RFC 4918, section 9.10.2):
     * a LOCK request carrying the lock token in the `If` header renews the lock instead of
     * creating a new one.
     *
     * @param token the lock token to refresh, as returned by [lock]
     * @param timeout requested new lifetime; the server may grant less
     */
    suspend fun refreshLock(
        path: String,
        token: String,
        timeout: Duration? = null,
    ): LockInfo {
        val headers = buildHeaders {
            append(DEPTH, Depth.ZERO.headerValue)
            append(IF_HEADER, "<$token>")
            if (timeout != null) append(TIMEOUT, "Second-${timeout.inWholeSeconds}")
        }
        // The body of a lock-refresh request is ignored by the server (RFC 4918 §9.10.2);
        // sending none avoids scope-mismatch rejections on servers that validate the
        // lockinfo against the existing lock.
        val response = execute(LOCK, path, headers = headers)
        ensureSuccess(response, LOCK, path)

        val headerToken = response.headers[LOCK_TOKEN]?.trim()?.removeSurrounding("<", ">")
        if (headerToken != null) {
            return LockInfo(token = headerToken, scope = LockScope.EXCLUSIVE, type = LockType.WRITE)
        }
        val body = response.bodyAsText()
        val info = MultiStatusParser.parseLockResponse(body)
        return if (info != null) {
            info
        } else {
            throw DavProtocolException("LOCK refresh on $path succeeded but no lock token was returned")
        }
    }

    /** Returns all active locks on [path] reported by the server (PROPFIND `lockdiscovery`). */
    suspend fun getLocks(path: String): List<LockInfo> {
        val file = getProperties(path, properties = listOf(PropertyName.LOCKDISCOVERY))
            ?: return emptyList()
        return file.locks
    }

    /**
     * Locks [path] for the duration of [block] and releases the lock afterwards.
     *
     * The lock is released in a `finally` block, so it is also released when [block]
     * throws; a failed UNLOCK (e.g. the server already released the lock) is tolerated
     * and never masks [block]'s exception or return value.
     */
    suspend fun <T> withLock(
        path: String,
        owner: String? = null,
        timeout: Duration? = null,
        scope: LockScope = LockScope.EXCLUSIVE,
        block: suspend (LockInfo) -> T,
    ): T {
        val lock = lock(path, owner, timeout, scope)
        try {
            return block(lock)
        } finally {
            runCatching { unlock(path, lock.token) }
        }
    }

    /** Releases a lock held by [token] on the resource at [path] (UNLOCK). */
    suspend fun unlock(path: String, token: String) {
        val response = execute(
            UNLOCK, path,
            headers = buildHeaders { append(LOCK_TOKEN, "<$token>") },
        )
        ensureSuccess(response, UNLOCK, path)
    }

    // ------------------------------------------------------------------ capability probe

    /** Probes the server capabilities at [path] and returns the allowed methods (OPTIONS). */
    suspend fun options(path: String = ""): Set<String> {
        val response = execute(HttpMethod.Options, path)
        ensureSuccess(response, HttpMethod.Options, path)
        return parseAllowHeader(response)
    }

    /**
     * Probes the server capabilities at [path] (OPTIONS) and reports both the allowed
     * methods and the advertised `DAV` compliance classes, which are the authoritative
     * source for WebDAV capability negotiation (1=class 1 basics, 2=class 2 locking,
     * 3=class 3 leased locks, extended-mkcol, ...).
     */
    suspend fun capabilities(path: String = ""): ServerCapabilities {
        val response = execute(HttpMethod.Options, path)
        ensureSuccess(response, HttpMethod.Options, path)
        return ServerCapabilities(
            allowedMethods = parseAllowHeader(response),
            davClasses = parseDavHeader(response),
        )
    }

    private fun parseAllowHeader(response: HttpResponse): Set<String> =
        response.headers[HttpHeaders.Allow]?.split(',')?.map { it.trim() }?.toSet().orEmpty()

    // Some servers quote DAV tokens (e.g. `DAV: "1", "2"`), so strip surrounding quotes.
    private fun parseDavHeader(response: HttpResponse): Set<String> =
        response.headers[DAV]?.split(',')
            ?.map { it.trim().removeSurrounding("\"") }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    // ------------------------------------------------------------------ convenience

    /** Issues a HEAD request and returns the response (headers only, no body). */
    suspend fun head(path: String): HttpResponse {
        val response = execute(HttpMethod.Head, path)
        ensureSuccess(response, HttpMethod.Head, path)
        return response
    }

    /** True when the resource at [path] exists (404 maps to false, other errors throw). */
    suspend fun exists(path: String): Boolean = try {
        getProperties(path) != null
    } catch (e: NotFoundException) {
        false
    }

    /** Lists the names of all properties of the resources at [path] (PROPFIND `propname`). */
    suspend fun getPropertyNames(
        path: String = "",
        depth: Depth = Depth.ONE,
    ): List<PropertyName> {
        val response = execute(
            PROPFIND, path,
            headers = buildHeaders {
                append(DEPTH, depth.headerValue)
                append(HttpHeaders.ContentType, "application/xml; charset=utf-8")
            },
            body = XmlBody.propfindPropName().toTextContent(),
        )
        ensureSuccess(response, PROPFIND, path)
        val multiStatus = MultiStatusParser.parseMultiStatus(response.bodyAsText())
        return multiStatus.responses
            .flatMap { it.propStats }
            .flatMap { it.props.keys }
            .distinct()
    }

    /** Quota information for the collection at [path], or null when the server reports none (RFC 4331). */
    suspend fun getQuota(path: String = ""): QuotaInfo? {
        val file = getProperties(
            path,
            properties = listOf(PropertyName.QUOTA_AVAILABLE_BYTES, PropertyName.QUOTA_USED_BYTES),
        ) ?: return null
        return QuotaInfo(
            availableBytes = file.properties[PropertyName.QUOTA_AVAILABLE_BYTES]?.text?.toLongOrNull(),
            usedBytes = file.properties[PropertyName.QUOTA_USED_BYTES]?.text?.toLongOrNull(),
        )
    }

    // ------------------------------------------------------------------ internals

    private fun String.toTextContent(): OutgoingContent =
        TextContent(this, ContentType.parse("application/xml; charset=utf-8"))

    /** Builds immutable headers via [HeadersBuilder]. */
    private fun buildHeaders(block: io.ktor.http.HeadersBuilder.() -> Unit): Headers =
        io.ktor.http.HeadersBuilder().apply(block).build()

    private fun destinationHeaders(
        destination: String,
        overwrite: Boolean,
        lockToken: String?,
        ifMatch: String?,
    ) = buildHeaders {
        append(DESTINATION, buildUrl(destination).toString())
        append(OVERWRITE, if (overwrite) "T" else "F")
        if (lockToken != null) append(IF_HEADER, "<$lockToken>")
        if (ifMatch != null) append(HttpHeaders.IfMatch, ifMatch)
    }

    /** Builds the absolute URL for a logical [path]. */
    private fun buildUrl(path: String): Url {
        if (path.isEmpty()) return baseUrl
        return URLBuilder().apply {
            protocol = baseUrl.protocol
            host = baseUrl.host
            port = baseUrl.port
            encodedPath = baseUrl.encodedPath
            appendPathSegments(path.split('/'))
        }.build()
    }

    /** Resolves a server-provided href (absolute or root-relative) against [baseUrl]. */
    private fun resolveHref(href: String): Url {
        if (href.startsWith("http://") || href.startsWith("https://")) return Url(href)
        return URLBuilder().apply {
            protocol = baseUrl.protocol
            host = baseUrl.host
            port = baseUrl.port
            encodedPath = if (href.startsWith("/")) href else baseUrl.encodedPath + href
        }.build()
    }

    /** The logical path of [target] relative to [baseUrl], or null if outside the base tree. */
    private fun relativePath(target: Url): String? {
        val basePath = baseUrl.encodedPath
        val targetPath = target.encodedPath
        if (targetPath == basePath) return ""
        if (!targetPath.startsWith(basePath)) return null
        val rel = targetPath.removePrefix(basePath)
        val decoded = rel.split('/').joinToString("/") { decodePathSegment(it) }
        return decoded.removeSuffix("/")
    }

    /** Percent-decodes a single path segment. */
    private fun decodePathSegment(segment: String): String {
        if ('%' !in segment) return segment
        val bytes = ByteArray(segment.length)
        var count = 0
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length + 1 && i + 2 <= segment.length &&
                isHexDigit(segment[i + 1]) && isHexDigit(segment[i + 2])
            ) {
                bytes[count++] = segment.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                for (b in c.toString().encodeToByteArray()) bytes[count++] = b
                i++
            }
        }
        return bytes.copyOf(count).decodeToString()
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Maps a parsed multi-status response entry to a [WebDavFile]. */
    private fun MultiStatusResponse.toWebDavFile(): WebDavFile? {
        if (href.isBlank()) return null
        val target = resolveHref(href)
        val path = relativePath(target) ?: return null
        val props = mutableMapOf<PropertyName, PropValue>()
        propStats.filter { it.isSuccess }.forEach { props.putAll(it.props) }
        val resourceType = props[PropertyName.RESOURCETYPE]
        val isDirectory = (resourceType as? PropValue.Node)
            ?.child(PropertyName.dav("collection")) != null
        val name = path.substringAfterLast('/')
        return WebDavFile(
            href = href,
            path = path,
            name = name,
            isDirectory = isDirectory,
            properties = props,
        )
    }

    /**
     * Executes a WebDAV request, following redirects manually (up to [WebDavClientConfig.maxRedirects]).
     * Request bodies are only sent on the first attempt; subsequent redirects are issued
     * without a body, which is safe for the common 301/302 cases where the resource moved.
     *
     * Unlike the higher-level methods, the response status is not checked; call
     * [ensureSuccess] yourself to map non-2xx statuses to [HttpStatusException].
     * This is the extension point for WebDAV methods without a dedicated wrapper
     * (e.g. REPORT/SEARCH) and for custom headers.
     */
    suspend fun execute(
        method: HttpMethod,
        path: String,
        headers: Headers = Headers.Empty,
        body: OutgoingContent? = null,
    ): HttpResponse {
        var currentPath = path
        var currentMethod = method
        var redirects = 0
        while (true) {
            val url = buildUrl(currentPath)
            val response = httpClient.request(url) {
                this.method = currentMethod
                headers.entries().forEach { (key, values) ->
                    values.forEach { value -> this.headers.append(key, value) }
                }
                if (body != null && redirects == 0) {
                    setBody(body)
                }
            }
            val status = response.status.value
            if (status in 300..399 && config.followRedirects && redirects < config.maxRedirects) {
                val location = response.headers[HttpHeaders.Location]
                if (location.isNullOrBlank()) return response
                response.bodyAsChannel().cancel()
                currentPath = relativePath(resolveHref(location)) ?: currentPath
                if (currentMethod == HttpMethod.Post && status == 303) {
                    currentMethod = HttpMethod.Get
                }
                redirects++
                continue
            }
            return response
        }
    }

    /**
     * Throws the specific [HttpStatusException] subclass for a non-success [HttpStatusCode]
     * (see [httpStatusException]); 2xx and 3xx pass through unchanged.
     */
    suspend fun ensureSuccess(response: HttpResponse, method: HttpMethod, path: String) {
        if (!response.status.isSuccess()) {
            val url = buildUrl(path).toString()
            val body = runCatching { response.bodyAsText().take(MAX_ERROR_BODY) }.getOrNull()
            throw httpStatusException(response.status, method, url, body)
        }
    }

    private suspend fun ByteReadChannel.readAll(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = readAvailable(buffer, 0, buffer.size)
            if (n == -1) break
            total += n
            chunks.add(buffer.copyOf(n))
        }
        val result = ByteArray(total.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
