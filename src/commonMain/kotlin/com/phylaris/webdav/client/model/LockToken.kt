package com.phylaris.webdav.client.model

/**
 * A write lock token obtained from a successful LOCK request.
 *
 * @param token the opaque lock token (the value of the `Lock-Token` response header,
 *   without angle brackets)
 * @param timeoutSeconds the server-provided timeout (may be null if not reported)
 * @param owner the owner string reported by the server, if any
 */
@Deprecated("Replaced by LockInfo, which also carries scope/type/depth; WebDavClient.lock() now returns LockInfo")
data class LockToken(
    val token: String,
    val timeoutSeconds: Long? = null,
    val owner: String? = null,
) {
    /** The token wrapped in angle brackets as required by the `If` header. */
    val ifHeaderValue: String get() = "<$token>"
}
