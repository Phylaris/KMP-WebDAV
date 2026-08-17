package com.phylaris.webdav.client

/**
 * Authentication schemes supported by the client.
 */
sealed interface Auth {

    /** No authentication. */
    data object None : Auth

    /** HTTP Basic authentication (RFC 7617). Should only be used over HTTPS. */
    data class Basic(val username: String, val password: String) : Auth

    /** HTTP Digest authentication (RFC 7616). */
    data class Digest(val username: String, val password: String) : Auth

    /** Bearer token authentication (OAuth2 / OIDC), with a lazily refreshed token provider. */
    data class Bearer(val tokenProvider: () -> String) : Auth
}
