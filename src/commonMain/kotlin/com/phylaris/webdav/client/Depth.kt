package com.phylaris.webdav.client

/**
 * WebDAV Depth header values used by PROPFIND and other requests.
 */
enum class Depth(val headerValue: String) {
    ZERO("0"),
    ONE("1"),
    INFINITY("infinity"),
}
