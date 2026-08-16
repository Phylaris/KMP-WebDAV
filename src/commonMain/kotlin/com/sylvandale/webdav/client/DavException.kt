package com.sylvandale.webdav.client

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * Base class for all exceptions thrown by this library.
 */
open class DavException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Thrown when the server response could not be parsed (invalid XML, malformed multi-status, ...). */
class DavProtocolException(
    message: String,
    cause: Throwable? = null,
) : DavException(message, cause)

/**
 * Base class for HTTP error responses (4xx/5xx).
 */
open class HttpStatusException(
    val status: HttpStatusCode,
    val method: HttpMethod,
    val url: String,
    val responseBody: String? = null,
) : DavException(
    "HTTP ${status.value} ${status.description} for ${method.value} $url" +
        (responseBody?.let { ": ${it.take(200)}" } ?: ""),
)

/** 401 Unauthorized — credentials missing, invalid or rejected. */
class UnauthorizedException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.Unauthorized, method, url, responseBody)

/** 403 Forbidden. */
class ForbiddenException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.Forbidden, method, url, responseBody)

/** 404 Not Found — the resource does not exist. */
class NotFoundException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.NotFound, method, url, responseBody)

/** 405 Method Not Allowed. */
class MethodNotAllowedException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.MethodNotAllowed, method, url, responseBody)

/** 409 Conflict — e.g. creating a collection that already exists. */
class ConflictException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.Conflict, method, url, responseBody)

/** 412 Precondition Failed — e.g. overwrite of an existing resource without Overwrite: T. */
class PreconditionFailedException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.PreconditionFailed, method, url, responseBody)

/** 423 Locked — the resource is locked by another client. */
class LockedException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.Locked, method, url, responseBody)

/** 507 Insufficient Storage. */
class InsufficientStorageException(
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
) : HttpStatusException(HttpStatusCode.InsufficientStorage, method, url, responseBody)

/**
 * Maps a non-success [HttpStatusCode] to the most specific [HttpStatusException] subclass.
 */
internal fun httpStatusException(
    status: HttpStatusCode,
    method: HttpMethod,
    url: String,
    responseBody: String? = null,
): HttpStatusException = when (status.value) {
    401 -> UnauthorizedException(method, url, responseBody)
    403 -> ForbiddenException(method, url, responseBody)
    404 -> NotFoundException(method, url, responseBody)
    405 -> MethodNotAllowedException(method, url, responseBody)
    409 -> ConflictException(method, url, responseBody)
    412 -> PreconditionFailedException(method, url, responseBody)
    423 -> LockedException(method, url, responseBody)
    507 -> InsufficientStorageException(method, url, responseBody)
    else -> HttpStatusException(status, method, url, responseBody)
}
