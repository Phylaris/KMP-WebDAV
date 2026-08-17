package com.phylaris.webdav.client.model

/**
 * A file or collection (directory) on the WebDAV server, as returned by PROPFIND.
 *
 * @param href the raw href reported by the server (may be URL-encoded, possibly relative)
 * @param path the normalized path relative to the client base URL (URL-decoded, no leading slash)
 * @param name the last path segment of [path]
 * @param isDirectory whether the resource is a collection (has `resourcetype` containing `collection`)
 * @param properties all properties reported by the server, keyed by property name
 */
class WebDavFile(
    val href: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val properties: Map<PropertyName, PropValue>,
) {
    val displayName: String?
        get() = properties[PropertyName.DISPLAYNAME]?.text

    val contentLength: Long?
        get() = properties[PropertyName.GETCONTENTLENGTH]?.text?.toLongOrNull()

    /** RFC 1123 formatted modification time as reported by the server, e.g. "Mon, 12 Jan 1998 09:25:56 GMT". */
    val lastModified: String?
        get() = properties[PropertyName.GETLASTMODIFIED]?.text

    val etag: String?
        get() = properties[PropertyName.GETETAG]?.text

    val contentType: String?
        get() = properties[PropertyName.GETCONTENTTYPE]?.text

    val creationDate: String?
        get() = properties[PropertyName.CREATIONDATE]?.text

    /** True if the server reported an active lock on this resource. */
    val isLocked: Boolean
        get() = locks.isNotEmpty()

    /** All active locks reported by the server in the `lockdiscovery` property, if any. */
    val locks: List<LockInfo>
        get() = (properties[PropertyName.LOCKDISCOVERY] as? PropValue.Node)
            ?.children(PropertyName.dav("activelock"))
            ?.mapNotNull { LockInfo.fromActivelock(it) }
            ?: emptyList()

    override fun toString(): String = "WebDavFile(path=$path, isDirectory=$isDirectory)"
}
