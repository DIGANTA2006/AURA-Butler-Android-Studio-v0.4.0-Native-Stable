package `in`.diganta.aurabutler.runtime

import kotlinx.coroutines.CancellationException

/**
 * Converts ordinary suspend-operation failures into a Result while preserving
 * structured-concurrency cancellation. Never use runCatching around a suspend
 * boundary when cancellation should stop fallback work.
 */
suspend fun <T> runCatchingCancellable(
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
