package com.phylaris.webdav.client

/**
 * Configuration for [WebDavClient] protocol behavior.
 */
class WebDavClientConfig {

    /** Whether 3xx responses are followed manually (default true). */
    var followRedirects: Boolean = true

    /** Maximum number of redirects to follow before failing with [IllegalStateException]. */
    var maxRedirects: Int = 5
}
