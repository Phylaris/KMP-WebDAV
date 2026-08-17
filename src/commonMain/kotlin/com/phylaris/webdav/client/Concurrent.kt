package com.phylaris.webdav.client

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs [block] for each element with at most [concurrency] coroutines at a time.
 * Failures are propagated after all started blocks complete (structured concurrency).
 */
suspend fun <T> List<T>.mapConcurrent(
    concurrency: Int,
    block: suspend (T) -> Unit,
) {
    require(concurrency >= 1) { "concurrency must be >= 1" }
    val semaphore = Semaphore(concurrency)
    coroutineScope {
        map { item ->
            launch {
                semaphore.withPermit {
                    block(item)
                }
            }
        }.joinAll()
    }
}
