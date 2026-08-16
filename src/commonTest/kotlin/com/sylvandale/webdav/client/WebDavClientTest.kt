package com.sylvandale.webdav.client

import com.sylvandale.webdav.client.internal.ProgressListener
import com.sylvandale.webdav.client.model.PropertyName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavClientTest {

    private val baseUrl = "https://example.com/dav/"

    private val multiStatusXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/docs/</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>docs</d:displayname>
                <d:resourcetype><d:collection/></d:resourcetype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/docs/report.txt</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>report.txt</d:displayname>
                <d:getcontentlength>1234</d:getcontentlength>
                <d:getcontenttype>text/plain</d:getcontenttype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = WebDavClient(
        HttpClient(MockEngine) { engine { addHandler(handler) } },
        baseUrl,
    )

    // ---------------------------------------------------------------- list

    @Test
    fun listSendsPropfindWithDepthOneAndParsesFiles() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond(
                content = multiStatusXml,
                status = HttpStatusCode.MultiStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8"),
            )
        }

        val files = dav.list()

        assertEquals("PROPFIND", requests.single().method.value)
        assertEquals("1", requests.single().headers[HttpHeaders.Depth])
        assertEquals(2, files.size)

        val dir = files[0]
        assertEquals("docs", dir.path)
        assertEquals("docs", dir.name)
        assertTrue(dir.isDirectory)

        val file = files[1]
        assertEquals("docs/report.txt", file.path)
        assertFalse(file.isDirectory)
        assertEquals(1234L, file.contentLength)
        assertEquals("text/plain", file.contentType)
        assertEquals("report.txt", file.displayName)
    }

    @Test
    fun listExcludesTheCollectionItself() = runTest {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>https://example.com/dav/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/a.txt</d:href>
                <d:propstat>
                  <d:prop><d:displayname>a.txt</d:displayname></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val dav = client {
            respond(xml, HttpStatusCode.MultiStatus)
        }
        val files = dav.list()
        assertEquals(1, files.size)
        assertEquals("a.txt", files.single().path)
    }

    @Test
    fun listExcludesTheTargetCollectionItself() = runTest {
        // A Depth-1 PROPFIND returns the target collection itself plus its
        // members; list() must exclude the target, not just the base root.
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/docs/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/docs/a.txt</d:href>
                <d:propstat>
                  <d:prop><d:displayname>a.txt</d:displayname></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val dav = client { respond(xml, HttpStatusCode.MultiStatus) }
        val files = dav.list("docs")
        assertEquals(1, files.size)
        assertEquals("docs/a.txt", files.single().path)
    }

    @Test
    fun listDecodesUrlEncodedHrefs() = runTest {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/my%20doc.txt</d:href>
                <d:propstat>
                  <d:prop><d:displayname>x</d:displayname></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val dav = client { respond(xml, HttpStatusCode.MultiStatus) }
        val files = dav.list()
        assertEquals("my doc.txt", files.single().path)
        assertEquals("my doc.txt", files.single().name)
    }

    @Test
    fun listThrowsNotFoundOn404() = runTest {
        val dav = client { respond("nope", HttpStatusCode.NotFound) }
        assertFailsWith<NotFoundException> { dav.list() }
    }

    @Test
    fun listThrowsUnauthorizedOn401() = runTest {
        val dav = client { respond("auth required", HttpStatusCode.Unauthorized) }
        assertFailsWith<UnauthorizedException> { dav.list() }
    }

    // ---------------------------------------------------------------- mutations

    @Test
    fun mkdirSendsMkCol() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.Created)
        }
        dav.mkdir("new dir")
        assertEquals("MKCOL", requests.single().method.value)
        assertEquals(
            "https://example.com/dav/new%20dir",
            requests.single().url.toString()
        )
    }

    @Test
    fun deleteSendsDelete() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.NoContent)
        }
        dav.delete("docs/report.txt")
        assertEquals("DELETE", requests.single().method.value)
        assertEquals("https://example.com/dav/docs/report.txt", requests.single().url.toString())
    }

    @Test
    fun moveSendsAbsoluteDestinationAndOverwriteHeader() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.Created)
        }
        dav.move("a.txt", "b/c.txt", overwrite = false)
        val request = requests.single()
        assertEquals("MOVE", request.method.value)
        assertEquals("https://example.com/dav/b/c.txt", request.headers["Destination"])
        assertEquals("F", request.headers["Overwrite"])
    }

    @Test
    fun copySendsDepthInfinity() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.Created)
        }
        dav.copy("docs", "docs-copy")
        val request = requests.single()
        assertEquals("COPY", request.method.value)
        assertEquals("infinity", request.headers["Depth"])
        assertEquals("T", request.headers["Overwrite"])
    }

    // ---------------------------------------------------------------- upload

    @Test
    fun uploadSendsIfNoneMatchWhenNotOverwrite() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.Created)
        }
        dav.uploadBytes(
            "f.txt", "hello".encodeToByteArray(),
            contentType = ContentType.Text.Plain,
            overwrite = false,
        )
        val request = requests.single()
        assertEquals("PUT", request.method.value)
        assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun uploadSendsBodyBytes() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.Created)
        }
        dav.uploadBytes("f.txt", byteArrayOf(1, 2, 3, 4))
        val bytes = requests.single().body.toByteArray()
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), bytes.toList())
    }

    @Test
    fun uploadFromChannelSetsContentTypeHeader() = runTest {
        // Regression: ChannelReadContent.contentType must not recurse infinitely.
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            // Consume the body so the engine materializes the request fully.
            request.body.toByteArray()
            requests.add(request)
            respond("", HttpStatusCode.Created)
        }
        val channel = io.ktor.utils.io.ByteChannel()
        channel.writeFully(byteArrayOf(1, 2, 3))
        channel.flushAndClose()
        dav.uploadFromChannel(
            "f.bin", channel,
            contentLength = 3,
            contentType = ContentType.Application.OctetStream,
        )
        // The body's content type is propagated to the request Content-Type header.
        val bodyContentType = requests.single().body.contentType
        assertEquals("application/octet-stream", bodyContentType?.toString())
    }

    @Test
    fun uploadFromChannelWithProgressReportsBytes() = runTest {
        val dav = client { request ->
            // Consume the body so the progress-relay coroutine actually runs.
            request.body.toByteArray()
            respond("", HttpStatusCode.Created)
        }
        val channel = io.ktor.utils.io.ByteChannel()
        channel.writeFully(ByteArray(256) { it.toByte() })
        channel.flushAndClose()
        val progress = mutableListOf<Pair<Long, Long?>>()
        dav.uploadFromChannel(
            "f.bin", channel,
            contentLength = 256,
            contentType = ContentType.Application.OctetStream,
            onProgress = ProgressListener { transferred, total -> progress.add(transferred to total) },
        )
        assertEquals(256L, progress.lastOrNull()?.first)
        assertEquals(256L, progress.lastOrNull()?.second)
    }

    @Test
    fun uploadThrowsPreconditionFailedWhenServerRejectsOverwrite() = runTest {
        val dav = client { respond("locked out", HttpStatusCode.PreconditionFailed) }
        assertFailsWith<PreconditionFailedException> {
            dav.uploadBytes("f.txt", byteArrayOf(1), overwrite = false)
        }
    }

    // ---------------------------------------------------------------- download

    @Test
    fun downloadSendsRangeHeader() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("partial", HttpStatusCode.PartialContent)
        }
        dav.downloadBytes("f.txt", range = 100L..199L)
        assertEquals("bytes=100-199", requests.single().headers[HttpHeaders.Range])
    }

    @Test
    fun downloadBytesReturnsFullContent() = runTest {
        val dav = client {
            respond(
                "file-content",
                HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "12")
            )
        }
        val bytes = dav.downloadBytes("f.txt")
        assertEquals("file-content", bytes.decodeToString())
    }

    // ---------------------------------------------------------------- proppatch

    @Test
    fun setPropertiesSendsProppatch() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond(
                """<d:multistatus xmlns:d="DAV:">
                    <d:response>
                      <d:href>/dav/f.txt</d:href>
                      <d:propstat>
                        <d:prop><d:displayname>new name</d:displayname></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                      </d:propstat>
                    </d:response>
                  </d:multistatus>""",
                HttpStatusCode.MultiStatus,
            )
        }
        dav.setProperties("f.txt", set = mapOf(PropertyName.DISPLAYNAME to "new name"))
        val request = requests.single()
        assertEquals("PROPPATCH", request.method.value)
        val bodyText = request.body.toByteReadPacket()
            .readByteArray().decodeToString()
        assertTrue(bodyText.contains("propertyupdate"))
        assertTrue(bodyText.contains("<d:displayname>new name</d:displayname>"))
    }

    @Test
    fun setPropertiesThrowsWhenPartialFailure() = runTest {
        val dav = client {
            respond(
                """<d:multistatus xmlns:d="DAV:">
                    <d:response>
                      <d:href>/dav/f.txt</d:href>
                      <d:propstat>
                        <d:prop><d:displayname>new</d:displayname></d:prop>
                        <d:status>HTTP/1.1 403 Forbidden</d:status>
                      </d:propstat>
                    </d:response>
                  </d:multistatus>""",
                HttpStatusCode.MultiStatus,
            )
        }
        assertFailsWith<DavProtocolException> {
            dav.setProperties("f.txt", set = mapOf(PropertyName.DISPLAYNAME to "new"))
        }
    }

    // ---------------------------------------------------------------- locking

    @Test
    fun lockParsesLockTokenHeader() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond(
                "<d:prop xmlns:d=\"DAV:\"><d:lockdiscovery/></d:prop>",
                HttpStatusCode.OK,
                headers = headersOf(
                    "Lock-Token" to listOf("<opaquelocktoken:12345>"),
                    HttpHeaders.ContentType to listOf("application/xml"),
                ),
            )
        }
        val token = dav.lock("f.txt", owner = "me")
        assertEquals("LOCK", requests.single().method.value)
        assertEquals("0", requests.single().headers["Depth"])
        assertEquals("opaquelocktoken:12345", token.token)
        assertEquals("<opaquelocktoken:12345>", token.ifHeaderValue)
    }

    @Test
    fun lockFallsBackToResponseBody() = runTest {
        val dav = client {
            respond(
                """<d:prop xmlns:d="DAV:">
                    <d:lockdiscovery>
                      <d:activelock>
                        <d:locktype><d:write/></d:locktype>
                        <d:lockscope><d:exclusive/></d:lockscope>
                        <d:depth>infinity</d:depth>
                        <d:timeout>Second-1800</d:timeout>
                        <d:locktoken><d:href>opaquelocktoken:body-token</d:href></d:locktoken>
                      </d:activelock>
                    </d:lockdiscovery>
                  </d:prop>""",
                HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val token = dav.lock("f.txt")
        assertEquals("opaquelocktoken:body-token", token.token)
        assertEquals(1800L, token.timeoutSeconds)
    }

    @Test
    fun unlockSendsLockTokenHeader() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            respond("", HttpStatusCode.NoContent)
        }
        dav.unlock("f.txt", "opaquelocktoken:12345")
        val request = requests.single()
        assertEquals("UNLOCK", request.method.value)
        assertEquals("<opaquelocktoken:12345>", request.headers["Lock-Token"])
    }

    // ---------------------------------------------------------------- redirects & options

    @Test
    fun followsRedirectsManually() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val dav = client { request ->
            requests.add(request)
            if (requests.size == 1) {
                respond(
                    "",
                    HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "/dav/moved/"),
                )
            } else {
                respond(multiStatusXml, HttpStatusCode.MultiStatus)
            }
        }
        val files = dav.list()
        assertEquals(2, requests.size)
        // Redirect target is a logical path, so no trailing slash.
        assertEquals("https://example.com/dav/moved", requests[1].url.toString())
        assertEquals(2, files.size)
    }

    @Test
    fun optionsReturnsAllowSet() = runTest {
        val dav = client {
            respond(
                "",
                HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.Allow, "OPTIONS, GET, HEAD, PROPFIND, PUT, DELETE"),
            )
        }
        val allowed = dav.options()
        assertTrue(allowed.contains("PROPFIND"))
        assertTrue(allowed.contains("PUT"))
        assertTrue(!allowed.contains("LOCK"))
    }

    @Test
    fun listRecursiveWalksDirectoriesWithConcurrency() = runTest {
        val directories = mutableListOf<String>()
        val dav = client { request ->
            val path = request.url.encodedPath.removePrefix("/dav/").trimEnd('/')
            directories.add(path)
            val xml = when (path) {
                "" -> """
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>/dav/sub/</d:href><d:propstat>
                        <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                      <d:response><d:href>/dav/a.txt</d:href><d:propstat>
                        <d:prop><d:displayname>a.txt</d:displayname></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    </d:multistatus>
                """.trimIndent()
                "sub" -> """
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>/dav/sub/b.txt</d:href><d:propstat>
                        <d:prop><d:displayname>b.txt</d:displayname></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    </d:multistatus>
                """.trimIndent()
                else -> """<d:multistatus xmlns:d="DAV:"/>"""
            }
            respond(xml, HttpStatusCode.MultiStatus)
        }
        val files = dav.listRecursive(concurrency = 2)
        assertEquals(setOf("", "sub"), directories.toSet())
        assertEquals(3, files.size)
        assertTrue(files.any { it.path == "sub/b.txt" })
    }
}
