package com.phylaris.webdav.client

import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests against a real WebDAV server (e.g. Jianguoyun / 坚果云).
 *
 * Credentials are resolved from, in order:
 *  1. the environment variables `WEBDAV_TEST_URL`, `WEBDAV_TEST_USERNAME` and
 *     `WEBDAV_TEST_PASSWORD`;
 *  2. a local `webdav-test.properties` in the project root (git-ignored; see
 *     `webdav-test.properties.example` for the format).
 *
 * When neither source provides credentials, every test is skipped so the
 * regular `jvmTest` run stays green on machines without server access.
 *
 * Some servers throttle WebDAV — Jianguoyun's free tier answers HTTP 503
 * `BlockedTemporarily` after bursts (official limit: 600 requests per 30
 * minutes) — so this suite spaces requests out a little and the
 * setup/teardown mkdir/delete calls retry on 503.
 */
class RealServerWebDavClientTest {

    private lateinit var dav: WebDavClient

    /** Per-run test directory, e.g. `webdav-client-tests-1f3a9c2b`. */
    private lateinit var root: String

    @BeforeTest
    fun setUp() {
        val config = loadConfig()
        Assume.assumeTrue(
            "No WebDAV credentials; set WEBDAV_TEST_URL/USERNAME/PASSWORD or create webdav-test.properties",
            config != null,
        )
        val c = config!!
        dav = WebDavClient.create(c.url, Auth.Basic(c.username, c.password))
        // Small gap between tests keeps rate-limited servers (e.g. Jianguoyun) happy.
        Thread.sleep(1_000)
        // Single-level directory (MKCOL needs the parent collection to exist).
        root = "webdav-client-tests-${UUID.randomUUID().toString().take(8)}"
        runBlocking { retryOnThrottle { dav.mkdir(root) } }
    }

    @AfterTest
    fun tearDown() {
        if (::dav.isInitialized && ::root.isInitialized) {
            runCatching { runBlocking { retryOnThrottle { dav.delete(root) } } }
            dav.httpClient.close()
        }
    }

    private fun path(rel: String) = "$root/$rel"

    // ------------------------------------------------------------- capabilities & listing

    @Test
    fun optionsAndListProbe() = runTest {
        // Allow headers are unreliable: fnos omits PUT/MKCOL/GET although they
        // work, so only PROPFIND (always advertised) is asserted here.
        val allowed = dav.options(root)
        assertTrue(allowed.contains("PROPFIND"), "Allow: $allowed")

        val paths = dav.list().map { it.path }
        assertTrue(paths.contains(root), "root listing: $paths")
    }

    // ------------------------------------------------------------- directories

    @Test
    fun mkdirListDeleteRoundTrip() = runTest {
        val dir = path("sub dir") // spaces exercise URL encoding
        dav.mkdir(dir)
        val created = dav.list(root).first { it.path == dir }
        assertTrue(created.isDirectory)
        dav.delete(dir)
    }

    // ------------------------------------------------------------- upload / download

    @Test
    fun uploadDownloadRoundTrip() = runTest {
        val name = "uploaded 文件.txt" // unicode + spaces
        val content = "hello from KMP-WebDAV 你好".encodeToByteArray()
        dav.uploadBytes(path(name), content, contentType = ContentType.Text.Plain)

        val listed = dav.list(root).first { it.path == path(name) }
        assertFalse(listed.isDirectory)
        assertEquals(content.size.toLong(), listed.contentLength)
        // Some servers echo the charset back, e.g. "text/plain; charset=utf-8".
        assertTrue(listed.contentType?.startsWith("text/plain") == true, "contentType: ${listed.contentType}")

        assertContentEquals(content, dav.downloadBytes(path(name)))
    }

    @Test
    fun rangeDownloadIsAccepted() = runTest {
        val content = "0123456789abcdefghij".encodeToByteArray()
        dav.uploadBytes(path("range.bin"), content)
        val response = dav.download(path("range.bin"), range = 3L..8L)
        if (response.status == HttpStatusCode.PartialContent) {
            assertEquals("345678", response.bodyAsText())
        } else {
            // Servers may ignore Range and reply 200 with the full body.
            assertTrue(response.status.isSuccess(), "unexpected status ${response.status}")
        }
    }

    // ------------------------------------------------------------- move / copy

    @Test
    fun moveAndCopyRoundTrip() = runTest {
        val src = path("move-src.txt")
        val dst = path("move-dst.txt")
        dav.uploadBytes(src, "moved".encodeToByteArray())
        dav.move(src, dst)
        val paths = dav.list(root).map { it.path }
        assertTrue(paths.contains(dst), "after move: $paths")
        assertTrue(paths.none { it == src })

        try {
            dav.copy(dst, path("copy-dst.txt"))
            val afterCopy = dav.list(root).map { it.path }
            assertTrue(afterCopy.contains(dst) && afterCopy.contains(path("copy-dst.txt")), "after copy: $afterCopy")
        } catch (e: LockedException) {
            // fnos answers 423 Locked for every COPY (server limitation).
            Assume.assumeTrue("server does not support COPY (HTTP 423 Locked)", false)
        }
    }

    // ------------------------------------------------------------- recursive listing

    @Test
    fun listRecursiveWalksSubtree() = runTest {
        dav.mkdir(path("nested"))
        dav.uploadBytes(path("nested/leaf.txt"), "leaf".encodeToByteArray())

        val paths = dav.listRecursive(root, concurrency = 2).map { it.path }.toSet()
        assertTrue(paths.contains(path("nested")), "recursive listing: $paths")
        assertTrue(paths.contains(path("nested/leaf.txt")))
    }

    // ------------------------------------------------------------- locking

    @Test
    fun lockAndUnlockRoundTrip() = runTest {
        dav.uploadBytes(path("lock.txt"), "locked".encodeToByteArray())
        val token = dav.lock(path("lock.txt"), owner = "KMP-WebDAV integration test")
        assertTrue(token.token.isNotBlank(), "lock token missing")
        dav.unlock(path("lock.txt"), token.token)
    }

    @Test
    fun lockThenWriteWithTokenThenUnlock() = runTest {
        dav.uploadBytes(path("locked-write.txt"), "v1".encodeToByteArray())
        val lock = dav.lock(path("locked-write.txt"), owner = "KMP-WebDAV integration test")
        try {
            // Write operations on a locked resource must carry the lock token
            // (the client sends `If: (<token>)`; servers reject 423 without it).
            try {
                dav.uploadBytes(path("locked-write.txt"), "v2".encodeToByteArray(), lockToken = lock.token)
                assertContentEquals("v2".encodeToByteArray(), dav.downloadBytes(path("locked-write.txt")))
            } catch (e: HttpStatusException) {
                // Some servers (e.g. fnos) answer 400 Bad Request for the If header
                // although they advertise LOCK; that is a server limitation, skip.
                Assume.assumeTrue("server rejects If-header writes (HTTP ${e.status.value})", false)
            }
        } finally {
            runCatching { dav.unlock(path("locked-write.txt"), lock.token) }
        }
    }

    // ------------------------------------------------------------- capabilities

    @Test
    fun capabilitiesProbe() = runTest {
        val caps = dav.capabilities(root)
        assertTrue(caps.allowedMethods.contains("PROPFIND"), "Allow: ${caps.allowedMethods}")
        // The DAV compliance header is optional; when present it must parse cleanly.
        caps.davClasses.forEach { assertTrue(it.isNotBlank(), "blank DAV class") }
    }

    // ------------------------------------------------------------- configuration

    /**
     * Jianguoyun throttles with 503 `BlockedTemporarily`; waits 30/60 s and
     * retries twice so a cooling-down server does not fail the test run.
     */
    private suspend fun <T> retryOnThrottle(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HttpStatusException) {
                if (e.status != HttpStatusCode.ServiceUnavailable || attempt >= 2) throw e
                attempt++
                delay(30_000L * attempt)
            }
        }
    }

    private data class ServerConfig(val url: String, val username: String, val password: String)

    private fun loadConfig(): ServerConfig? {
        val env = listOf("WEBDAV_TEST_URL", "WEBDAV_TEST_USERNAME", "WEBDAV_TEST_PASSWORD")
            .map { System.getenv(it) }
        val envCount = env.count { !it.isNullOrBlank() }
        require(envCount == 0 || envCount == 3) {
            "WEBDAV_TEST_URL, WEBDAV_TEST_USERNAME and WEBDAV_TEST_PASSWORD must all be set together"
        }
        if (envCount == 3) {
            return ServerConfig(env[0]!!, env[1]!!, env[2]!!)
        }
        val file = File("webdav-test.properties")
        if (file.isFile) {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val url = props.getProperty("url")
            val username = props.getProperty("username")
            val password = props.getProperty("password")
            require(!url.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
                "webdav-test.properties must define url, username and password"
            }
            return ServerConfig(url, username, password)
        }
        return null
    }
}
