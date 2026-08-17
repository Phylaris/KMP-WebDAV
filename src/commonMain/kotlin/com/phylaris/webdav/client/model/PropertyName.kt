package com.phylaris.webdav.client.model

/**
 * A WebDAV XML property name: a (namespace, local name) pair.
 */
data class PropertyName(
    val namespace: String,
    val name: String,
) {
    override fun toString(): String = "{$namespace}$name"

    companion object {
        const val DAV_NAMESPACE = "DAV:"

        fun dav(name: String) = PropertyName(DAV_NAMESPACE, name)

        /** Standard dead properties (RFC 4918, section 15). */
        val CREATIONDATE = dav("creationdate")
        val DISPLAYNAME = dav("displayname")
        val GETCONTENTLANGUAGE = dav("getcontentlanguage")
        val GETCONTENTLENGTH = dav("getcontentlength")
        val GETCONTENTTYPE = dav("getcontenttype")
        val GETETAG = dav("getetag")
        val GETLASTMODIFIED = dav("getlastmodified")
        val LOCKDISCOVERY = dav("lockdiscovery")
        val RESOURCETYPE = dav("resourcetype")
        val SUPPORTEDLOCK = dav("supportedlock")
    }
}
