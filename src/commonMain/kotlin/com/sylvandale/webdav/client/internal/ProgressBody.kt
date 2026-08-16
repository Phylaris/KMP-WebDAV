package com.sylvandale.webdav.client.internal

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A callback for reporting transfer progress.
 *
 * @param bytesTransferred number of bytes transferred so far
 * @param totalBytes total size if known, or null (e.g. chunked transfer, unknown content length)
 */
fun interface ProgressListener {
    fun onProgress(bytesTransferred: Long, totalBytes: Long?)
}

/**
 * A plain read-channel upload body without progress reporting.
 * The engine reads the channel directly.
 */
internal class ChannelReadContent(
    private val channel: ByteReadChannel,
    private val expectedLength: Long?,
    contentType: ContentType,
) : OutgoingContent.ReadChannelContent() {

    // Initializer (not a getter) so the constructor parameter is visible and
    // the property is not recursive.
    override val contentType: ContentType? = contentType
    override val contentLength: Long? = expectedLength

    override fun readFrom(): ByteReadChannel = channel
}

/**
 * An upload body that relays a source channel into a fresh channel while reporting
 * progress as the engine consumes it.
 */
internal class ProgressReadChannelContent(
    private val channel: ByteReadChannel,
    private val expectedLength: Long?,
    contentType: ContentType,
    private val onProgress: ProgressListener,
) : OutgoingContent.ReadChannelContent() {

    override val contentType: ContentType? = contentType
    override val contentLength: Long? = expectedLength

    override fun readFrom(): ByteReadChannel {
        val result = ByteChannel()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val buffer = ByteArray(64 * 1024)
            var transferred = 0L
            try {
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n == -1) break
                    transferred += n
                    onProgress.onProgress(transferred, expectedLength)
                    result.writeFully(buffer, 0, n)
                }
                result.close()
            } catch (e: Throwable) {
                result.close(e)
            }
        }
        return result
    }
}

/** Writes a channel into a sink channel, reporting progress, and closes the sink. */
internal suspend fun ByteReadChannel.copyWithProgress(
    sink: ByteWriteChannel,
    expectedLength: Long?,
    onProgress: ProgressListener,
) {
    val buffer = ByteArray(64 * 1024)
    var transferred = 0L
    try {
        while (true) {
            val n = readAvailable(buffer, 0, buffer.size)
            if (n == -1) break
            transferred += n
            onProgress.onProgress(transferred, expectedLength)
            sink.writeFully(buffer, 0, n)
        }
        sink.flushAndClose()
    } catch (e: CancellationException) {
        sink.close(e)
        throw e
    } catch (e: Throwable) {
        sink.close(e)
        throw e
    }
}

/**
 * Reads a channel fully into memory and returns the byte array, reporting progress.
 * Intended for small payloads (e.g. text files); large downloads should use
 * [com.sylvandale.webdav.client.WebDavClient.downloadToChannel] instead.
 */
internal suspend fun ByteReadChannel.readBytesWithProgress(
    expectedLength: Long?,
    onProgress: ProgressListener,
): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = readAvailable(buffer, 0, buffer.size)
        if (n == -1) break
        total += n
        onProgress.onProgress(total, expectedLength)
        chunks.add(buffer.copyOf(n))
    }
    val result = ByteArray(total.toInt())
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
