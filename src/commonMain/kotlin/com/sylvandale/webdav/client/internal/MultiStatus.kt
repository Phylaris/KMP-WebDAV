package com.sylvandale.webdav.client.internal

import com.sylvandale.webdav.client.model.PropValue
import com.sylvandale.webdav.client.model.PropertyName

/**
 * Parsed `DAV: multistatus` document.
 */
internal data class MultiStatus(
    val responses: List<MultiStatusResponse>,
)

internal data class MultiStatusResponse(
    val href: String,
    val propStats: List<PropStat>,
)

internal data class PropStat(
    val props: Map<PropertyName, PropValue>,
    /** Status line as reported by the server, e.g. "HTTP/1.1 200 OK". */
    val status: String,
) {
    /** Numeric status code parsed from [status], or null. */
    val statusCode: Int?
        get() = status.trim().substringAfterLast(' ').toIntOrNull()
            ?: status.split(' ').firstOrNull { it.all(Char::isDigit) }?.toIntOrNull()

    val isSuccess: Boolean
        get() = statusCode?.let { it in 200..299 } ?: false
}
