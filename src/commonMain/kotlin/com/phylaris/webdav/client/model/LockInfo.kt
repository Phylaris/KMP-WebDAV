package com.phylaris.webdav.client.model

import com.phylaris.webdav.client.Depth

/** The scope of a WebDAV write lock (RFC 4918, section 6.1). */
enum class LockScope { EXCLUSIVE, SHARED }

/**
 * The type of a WebDAV lock. RFC 4918 defines only the `write` lock type;
 * servers may report extensions, which are skipped.
 */
enum class LockType { WRITE }

/**
 * Full information about a WebDAV write lock, as reported by a LOCK response or
 * the `lockdiscovery` property.
 *
 * @param token the opaque lock token (the value of the `Lock-Token` header or the
 *   `locktoken` href, without angle brackets)
 * @param scope whether the lock is exclusive or shared
 * @param type the lock type (only [LockType.WRITE] is defined by RFC 4918)
 * @param depth the depth reported for the lock, when known
 * @param timeoutSeconds the server-provided timeout (may be null if not reported)
 * @param owner the owner string reported by the server, if any
 * @param lockRootHref the href of the lock root, if reported
 */
data class LockInfo(
    val token: String,
    val scope: LockScope,
    val type: LockType,
    val depth: Depth? = null,
    val timeoutSeconds: Long? = null,
    val owner: String? = null,
    val lockRootHref: String? = null,
) {
    /** The token wrapped in angle brackets as required by the `If` header. */
    val ifHeaderValue: String get() = "<$token>"

    companion object {
        /**
         * Parses a single `DAV: activelock` element from a `lockdiscovery` property
         * value, or null when no lock token could be found.
         */
        fun fromActivelock(node: PropValue.Node): LockInfo? {
            val token = node.child(PropertyName.dav("locktoken"))?.deepText()
                ?: return null
            val scope = if (node.child(PropertyName.dav("lockscope"))
                    ?.child(PropertyName.dav("shared")) != null
            ) {
                LockScope.SHARED
            } else {
                LockScope.EXCLUSIVE
            }
            val depth = when (node.child(PropertyName.dav("depth"))?.text) {
                Depth.ZERO.headerValue -> Depth.ZERO
                Depth.ONE.headerValue -> Depth.ONE
                Depth.INFINITY.headerValue -> Depth.INFINITY
                else -> null
            }
            val timeoutSeconds = node.child(PropertyName.dav("timeout"))?.text
                ?.removePrefix("Second-")?.toLongOrNull()
            val owner = node.child(PropertyName.dav("owner"))?.deepText()
            val lockRootHref = node.child(PropertyName.dav("lockroot"))?.deepText()
            return LockInfo(
                token = token,
                scope = scope,
                type = LockType.WRITE,
                depth = depth,
                timeoutSeconds = timeoutSeconds,
                owner = owner,
                lockRootHref = lockRootHref,
            )
        }

        /**
         * The concatenated text of this node and its descendants. `Node.text` only
         * covers direct text children, while `locktoken`/`href` nesting (and the
         * parser's element-vs-text representation) varies, so descend as needed.
         */
        private fun PropValue.Node.deepText(): String? =
            text ?: children.filterIsInstance<PropValue.Node>().firstNotNullOfOrNull { it.deepText() }
    }
}
