package com.phylaris.webdav.client

import com.phylaris.webdav.client.internal.MultiStatusParser
import com.phylaris.webdav.client.model.LockInfo
import com.phylaris.webdav.client.model.LockScope
import com.phylaris.webdav.client.model.LockType
import com.phylaris.webdav.client.model.PropValue
import com.phylaris.webdav.client.model.PropertyName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiStatusParserTest {

    @Test
    fun parsesStandardMultiStatus() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/docs/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>docs</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getlastmodified>Mon, 12 Jan 1998 09:25:56 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getcontentlength/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/docs/report.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>report.txt</d:displayname>
                    <d:getcontentlength>1234</d:getcontentlength>
                    <d:getetag>"abc-123"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val multiStatus = MultiStatusParser.parseMultiStatus(xml)
        assertEquals(2, multiStatus.responses.size)

        val dir = multiStatus.responses[0]
        assertEquals("/dav/docs/", dir.href)
        assertEquals(2, dir.propStats.size)
        val dirProps = dir.propStats[0].props
        assertEquals("docs", dirProps[PropertyName.DISPLAYNAME]?.text)
        assertNotNull(dirProps[PropertyName.RESOURCETYPE])
        assertEquals(404, dir.propStats[1].statusCode)
        assertTrue(dir.propStats[0].isSuccess)
        assertFalse(dir.propStats[1].isSuccess)

        val file = multiStatus.responses[1]
        assertEquals(1234L, file.propStats[0].props[PropertyName.GETCONTENTLENGTH]?.text?.toLongOrNull())
        assertEquals("\"abc-123\"", file.propStats[0].props[PropertyName.GETETAG]?.text)
    }

    @Test
    fun handlesUnknownNamespacesAndProperties() {
        val xml = """
            <multistatus xmlns="DAV:" xmlns:oc="http://owncloud.org/ns">
              <response>
                <href>/dav/f.txt</href>
                <propstat>
                  <prop>
                    <displayname>f.txt</displayname>
                    <oc:size>42</oc:size>
                    <oc:favorite>1</oc:favorite>
                  </prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()

        val multiStatus = MultiStatusParser.parseMultiStatus(xml)
        val props = multiStatus.responses[0].propStats[0].props
        assertEquals("f.txt", props[PropertyName.DISPLAYNAME]?.text)
        // Unknown-namespace properties are preserved with their namespace.
        assertEquals(
            "42",
            props[PropertyName("http://owncloud.org/ns", "size")]?.text
        )
        assertEquals(
            "1",
            props[PropertyName("http://owncloud.org/ns", "favorite")]?.text
        )
    }

    @Test
    fun decodesEntityEscapesInText() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/a%20b.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>a &amp; b &lt;c&gt;.txt</d:displayname>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val multiStatus = MultiStatusParser.parseMultiStatus(xml)
        val props = multiStatus.responses[0].propStats[0].props
        assertEquals("a & b <c>.txt", props[PropertyName.DISPLAYNAME]?.text)
    }

    @Test
    fun emptyMultiStatusIsValid() {
        val xml = """<d:multistatus xmlns:d="DAV:"></d:multistatus>"""
        val multiStatus = MultiStatusParser.parseMultiStatus(xml)
        assertTrue(multiStatus.responses.isEmpty())
    }

    @Test
    fun toleratesMissingHrefInResponse() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:propstat>
                  <d:prop><d:displayname>x</d:displayname></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val multiStatus = MultiStatusParser.parseMultiStatus(xml)
        assertEquals(1, multiStatus.responses.size)
        assertEquals("", multiStatus.responses[0].href)
    }

    @Test
    fun parsesLockResponse() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:prop xmlns:d="DAV:">
              <d:lockdiscovery>
                <d:activelock>
                  <d:locktype><d:write/></d:locktype>
                  <d:lockscope><d:exclusive/></d:lockscope>
                  <d:depth>infinity</d:depth>
                  <d:owner>alice</d:owner>
                  <d:timeout>Second-3600</d:timeout>
                  <d:locktoken><d:href>opaquelocktoken:e71d4fae-5dec-22d6-fea5-00a0c91e6be4</d:href></d:locktoken>
                  <d:lockroot><d:href>/dav/f.txt</d:href></d:lockroot>
                </d:activelock>
              </d:lockdiscovery>
            </d:prop>
        """.trimIndent()

        val info = requireNotNull(MultiStatusParser.parseLockResponse(xml))
        assertEquals("opaquelocktoken:e71d4fae-5dec-22d6-fea5-00a0c91e6be4", info.token)
        assertEquals(LockScope.EXCLUSIVE, info.scope)
        assertEquals(LockType.WRITE, info.type)
        assertEquals(Depth.INFINITY, info.depth)
        assertEquals(3600L, info.timeoutSeconds)
        assertEquals("alice", info.owner)
        assertEquals("/dav/f.txt", info.lockRootHref)
    }

    @Test
    fun parsesSharedLockResponse() {
        val xml = """
            <d:prop xmlns:d="DAV:">
              <d:lockdiscovery>
                <d:activelock>
                  <d:locktype><d:write/></d:locktype>
                  <d:lockscope><d:shared/></d:lockscope>
                  <d:depth>0</d:depth>
                  <d:timeout>Second-60</d:timeout>
                  <d:locktoken><d:href>opaquelocktoken:shared-1</d:href></d:locktoken>
                </d:activelock>
              </d:lockdiscovery>
            </d:prop>
        """.trimIndent()

        val info = requireNotNull(MultiStatusParser.parseLockResponse(xml))
        assertEquals("opaquelocktoken:shared-1", info.token)
        assertEquals(LockScope.SHARED, info.scope)
        assertEquals(Depth.ZERO, info.depth)
    }

    @Test
    fun parsesMultipleActiveLocksFromLockDiscovery() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/f.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:lockdiscovery>
                      <d:activelock>
                        <d:locktype><d:write/></d:locktype>
                        <d:lockscope><d:exclusive/></d:lockscope>
                        <d:depth>infinity</d:depth>
                        <d:timeout>Second-3600</d:timeout>
                        <d:locktoken><d:href>opaquelocktoken:lock-a</d:href></d:locktoken>
                      </d:activelock>
                      <d:activelock>
                        <d:locktype><d:write/></d:locktype>
                        <d:lockscope><d:shared/></d:lockscope>
                        <d:depth>0</d:depth>
                        <d:owner>team</d:owner>
                        <d:timeout>Second-1800</d:timeout>
                        <d:locktoken><d:href>opaquelocktoken:lock-b</d:href></d:locktoken>
                        <d:lockroot><d:href>/dav/f.txt</d:href></d:lockroot>
                      </d:activelock>
                    </d:lockdiscovery>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val multiStatus = MultiStatusParser.parseMultiStatus(xml)
        val lockDiscovery = multiStatus.responses[0].propStats[0].props[PropertyName.LOCKDISCOVERY]
        assertNotNull(lockDiscovery)
        val locks = (lockDiscovery as PropValue.Node)
            .children(PropertyName.dav("activelock"))
            .mapNotNull { LockInfo.fromActivelock(it) }
        assertEquals(2, locks.size)
        assertEquals("opaquelocktoken:lock-a", locks[0].token)
        assertEquals(LockScope.EXCLUSIVE, locks[0].scope)
        assertEquals(Depth.INFINITY, locks[0].depth)
        assertEquals("opaquelocktoken:lock-b", locks[1].token)
        assertEquals(LockScope.SHARED, locks[1].scope)
        assertEquals("team", locks[1].owner)
        assertEquals("/dav/f.txt", locks[1].lockRootHref)
    }

    @Test
    fun returnsNullForLockResponseWithoutToken() {
        val xml = """<d:prop xmlns:d="DAV:"><d:lockdiscovery/></d:prop>"""
        assertNull(MultiStatusParser.parseLockResponse(xml))
    }
}
