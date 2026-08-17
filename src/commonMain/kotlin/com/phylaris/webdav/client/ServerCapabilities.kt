package com.phylaris.webdav.client

/**
 * Capabilities of a WebDAV server as negotiated via OPTIONS.
 *
 * @param allowedMethods the methods listed in the `Allow` response header
 * @param davClasses the classes listed in the `DAV` response header (e.g. "1"
 *   for class 1 basics, "2" for class 2 locking, "3" for class 3 leased locks,
 *   "extended-mkcol", ...); empty when the server reports none
 */
data class ServerCapabilities(
    val allowedMethods: Set<String>,
    val davClasses: Set<String>,
) {
    /**
     * True when the server supports locking: it advertises DAV class 2, or the
     * `Allow` header lists both LOCK and UNLOCK.
     */
    val supportsLocking: Boolean
        get() = davClasses.contains("2") ||
            (allowedMethods.contains("LOCK") && allowedMethods.contains("UNLOCK"))
}
