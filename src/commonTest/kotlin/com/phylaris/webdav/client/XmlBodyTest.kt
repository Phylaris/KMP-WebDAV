package com.phylaris.webdav.client

import com.phylaris.webdav.client.internal.XmlBody
import com.phylaris.webdav.client.model.LockScope
import com.phylaris.webdav.client.model.PropertyName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmlBodyTest {

    @Test
    fun escapesXmlSpecialCharacters() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", XmlBody.escape("a&b<c>d\"e'f"))
    }

    @Test
    fun propfindAllPropUsesDavNamespace() {
        val body = XmlBody.propfindAllProp()
        assertContains(body, "propfind")
        assertContains(body, "allprop")
        assertContains(body, """xmlns:d="DAV:"""")
    }

    @Test
    fun propfindPropsIncludesRequestedProperties() {
        val body = XmlBody.propfindProps(
            listOf(PropertyName.DISPLAYNAME, PropertyName.GETCONTENTLENGTH)
        )
        assertContains(body, "<d:displayname/>")
        assertContains(body, "<d:getcontentlength/>")
        assertFalse(body.contains("allprop"))
    }

    @Test
    fun propfindPropsHandlesCustomNamespaces() {
        val body = XmlBody.propfindProps(
            listOf(PropertyName("http://owncloud.org/ns", "favorite"))
        )
        assertContains(body, "<x:favorite")
        assertContains(body, """xmlns:x="http://owncloud.org/ns"""")
    }

    @Test
    fun proppatchBuildsSetAndRemoveSections() {
        val body = XmlBody.proppatch(
            set = mapOf(
                PropertyName.DISPLAYNAME to "My & File",
                PropertyName("http://example.com/ns", "custom") to "v1",
            ),
            remove = setOf(PropertyName.GETETAG),
        )
        assertContains(body, "propertyupdate")
        assertContains(body, "<d:set>")
        assertContains(body, "<d:displayname>My &amp; File</d:displayname>")
        assertContains(body, "<x:custom")
        assertContains(body, ">v1</x:custom>")
        assertContains(body, "<d:remove>")
        assertContains(body, "<d:getetag/>")
    }

    @Test
    fun lockBodyContainsExclusiveWriteLock() {
        val body = XmlBody.lock(owner = "my-app")
        assertContains(body, "lockinfo")
        assertContains(body, "lockscope")
        assertContains(body, "exclusive")
        assertContains(body, "locktype")
        assertContains(body, "write")
        assertContains(body, "<d:owner>my-app</d:owner>")
    }

    @Test
    fun lockBodyOmitsOwnerWhenNull() {
        val body = XmlBody.lock(owner = null)
        assertTrue(!body.contains("owner"))
    }

    @Test
    fun lockBodyDefaultsToExclusiveScope() {
        val body = XmlBody.lock(owner = null)
        assertContains(body, "<d:exclusive/>")
        assertTrue(!body.contains("shared"))
    }

    @Test
    fun lockBodySupportsSharedScope() {
        val body = XmlBody.lock(owner = "my-app", scope = LockScope.SHARED)
        assertContains(body, "lockscope")
        assertContains(body, "<d:shared/>")
        assertTrue(!body.contains("exclusive"))
    }
}
