package `in`.diganta.aurabutler.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object EmbeddingModelFiles {
    const val MODEL_NAME = "all-MiniLM-L6-v2-qint8-arm64.onnx"
    const val VOCAB_NAME = "all-MiniLM-L6-v2-vocab.txt"
    const val WORK_NAME = "aura-verified-semantic-embedding-model-v1"

    const val MODEL_SHA256 =
        "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"
    const val VOCAB_SHA256 =
        "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"

    private const val MODEL_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
    private const val MODEL_BASE =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/" +
            MODEL_REVISION + "/"

    fun modelFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "embeddings"), MODEL_NAME)

    fun vocabularyFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "embeddings"), VOCAB_NAME)

    fun completionMarker(context: Context): File =
        File(File(context.applicationContext.filesDir, "embeddings"), "model-installed-v1")

    fun isInstalled(context: Context): Boolean {
        val marker = completionMarker(context)
        val model = modelFile(context)
        val vocabulary = vocabularyFile(context)
        if (
            !marker.isFile ||
            !model.isFile ||
            model.length() <= OnnxSentenceEmbeddingProvider.MIN_MODEL_BYTES ||
            !vocabulary.isFile ||
            vocabulary.length() !in OnnxSentenceEmbeddingProvider.MIN_VOCAB_BYTES..
                OnnxSentenceEmbeddingProvider.MAX_VOCAB_BYTES
        ) return false

        val manifest = runCatching {
            marker.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to
                        line.substring(separator + 1).trim()
                }
                .toMap()
        }.getOrDefault(emptyMap())

        return manifest["revision"] == MODEL_REVISION &&
            manifest["model_sha256"] == MODEL_SHA256 &&
            manifest["vocab_sha256"] == VOCAB_SHA256 &&
            sha256(model) == MODEL_SHA256 &&
            sha256(vocabulary) == VOCAB_SHA256
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff)
        }
    }

    fun enqueueIfMissing(context: Context) {
        // Do not hash the model on the main thread during Application startup.
        // The worker verifies the manifest and both artifacts on Dispatchers.IO.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<EmbeddingModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Cancel pending model provisioning when the owner disables semantic memory. */
    fun cancelPendingDownload(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    internal fun modelUrl(): String =
        MODEL_BASE + "onnx/model_qint8_arm64.onnx"

    internal fun vocabularyUrl(): String =
        MODEL_BASE + "vocab.txt"
}

/**
 * Downloads the official pinned MiniLM artifacts only on an unmetered network,
 * verifies both SHA-256 digests, and publishes them with atomic file replacement.
 */
class EmbeddingModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val directory = File(applicationContext.filesDir, "embeddings")
        val model = EmbeddingModelFiles.modelFile(applicationContext)
        val vocabulary = EmbeddingModelFiles.vocabularyFile(applicationContext)
        val marker = EmbeddingModelFiles.completionMarker(applicationContext)
        try {
            if (EmbeddingModelFiles.isInstalled(applicationContext)) {
                return@withContext Result.success()
            }
            directory.mkdirs().also { created ->
                if (!created && !directory.isDirectory) {
                    throw IOException("Could not create the private embedding-model directory.")
                }
            }
            marker.delete()
            downloadVerified(
                url = EmbeddingModelFiles.modelUrl(),
                destination = model,
                expectedSha256 = EmbeddingModelFiles.MODEL_SHA256,
                maximumBytes = MAX_MODEL_BYTES,
                progressKey = "model"
            )
            downloadVerified(
                url = EmbeddingModelFiles.vocabularyUrl(),
                destination = vocabulary,
                expectedSha256 = EmbeddingModelFiles.VOCAB_SHA256,
                maximumBytes = MAX_VOCAB_BYTES,
                progressKey = "vocabulary"
            )
            marker.writeText(
                "revision=1110a243fdf4706b3f48f1d95db1a4f5529b4d41\n" +
                    "model_sha256=" + EmbeddingModelFiles.MODEL_SHA256 + "\n" +
                    "vocab_sha256=" + EmbeddingModelFiles.VOCAB_SHA256
            )
            Result.success(
                Data.Builder()
                    .putBoolean("model_installed", true)
                    .putString("model_name", EmbeddingModelFiles.MODEL_NAME)
                    .build()
            )
        } catch (cancelled: CancellationException) {
            marker.delete()
            throw cancelled
        } catch (integrity: ModelIntegrityException) {
            marker.delete()
            Result.failure(
                Data.Builder().putString("error", integrity.message).build()
            )
        } catch (error: Exception) {
            marker.delete()
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder().putString(
                        "error",
                        error.message ?: "The semantic embedding model could not be installed."
                    ).build()
                )
            }
        }
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedSha256: String,
        maximumBytes: Long,
        progressKey: String
    ) {
        if (destination.isFile && sha256(destination) == expectedSha256) return
        val temporary = File(destination.parentFile, destination.name + ".download")
        temporary.delete()
        var current = url
        var redirects = 0
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val uri = URI(current)
                validateDownloadUri(uri)
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "AURA-Butler-Android/0.18 semantic-model")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                try {
                    val status = connection.responseCode
                    if (status in REDIRECT_CODES) {
                        if (redirects++ >= MAX_REDIRECTS) {
                            throw IOException("The model host returned too many redirects.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?.takeIf(String::isNotBlank)
                            ?: throw IOException("The model redirect did not include a Location header.")
                        current = uri.resolve(location).toString()
                        continue
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw IOException("The model download returned HTTP $status.")
                    }
                    val contentLength = connection.contentLengthLong
                    if (contentLength > maximumBytes) {
                        throw ModelIntegrityException("The model download exceeded its size limit.")
                    }
                    connection.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maximumBytes) {
                                    throw ModelIntegrityException(
                                        "The model download exceeded its size limit."
                                    )
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                if (total % PROGRESS_GRANULARITY < count) {
                                    setProgress(
                                        Data.Builder()
                                            .putString("artifact", progressKey)
                                            .putLong("downloaded_bytes", total)
                                            .build()
                                    )
                                }
                            }
                        }
                    }
                    break
                } finally {
                    connection.disconnect()
                }
            }

            val actual = digest.digest().joinToString("") {
                "%02x".format(Locale.ROOT, it.toInt() and 0xff)
            }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw ModelIntegrityException(
                    "SHA-256 verification failed for the downloaded " + progressKey + "."
                )
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun validateDownloadUri(uri: URI) {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val trustedHost = host == "huggingface.co" ||
            host.endsWith(".huggingface.co") ||
            host.endsWith(".hf.co")
        require(uri.scheme.equals("https", ignoreCase = true) && trustedHost) {
            "AURA blocked an untrusted embedding-model download redirect."
        }
        require(uri.userInfo.isNullOrBlank() && (uri.port == -1 || uri.port == 443)) {
            "AURA blocked an unsafe embedding-model download URL."
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }

    private class ModelIntegrityException(message: String) : IOException(message)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 90_000
        const val MAX_REDIRECTS = 6
        const val BUFFER_BYTES = 32 * 1024
        const val PROGRESS_GRANULARITY = 1_048_576L
        const val MAX_MODEL_BYTES = 40L * 1024 * 1024
        const val MAX_VOCAB_BYTES = 1L * 1024 * 1024
        const val MAX_RETRIES = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
