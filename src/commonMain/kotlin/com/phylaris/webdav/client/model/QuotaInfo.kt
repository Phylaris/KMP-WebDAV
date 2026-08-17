package com.phylaris.webdav.client.model

/**
 * Storage quota information for a collection (RFC 4331).
 *
 * @param availableBytes the value of `quota-available-bytes`, or null when the
 *   server does not report it (e.g. unlimited quota)
 * @param usedBytes the value of `quota-used-bytes`, or null when not reported
 */
data class QuotaInfo(
    val availableBytes: Long?,
    val usedBytes: Long?,
)
