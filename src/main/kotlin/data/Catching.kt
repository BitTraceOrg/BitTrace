package org.bittrace.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching`, minus the one exception it must never catch.
 *
 * `runCatching` swallows [CancellationException], which turns Stop into a
 * failure: the job completes normally, its completion handler sees no cause,
 * and the caller reports an error for something the user asked for. Catching it
 * also breaks structured concurrency, since a cancelled scope is supposed to
 * keep unwinding.
 *
 * Written once for the OAuth flow, which is cancellable by a Stop button. The
 * git layer is cancellable for the same reason and had the same bug, which is
 * why this now lives somewhere both can reach.
 */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
