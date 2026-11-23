package `in`.diganta.aurabutler.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object ImageInputReader {
    data class PreparedImage(
        val bytes: ByteArray,
        val mimeType: String
    )

    fun fromUri(context: Context, uri: Uri): PreparedImage {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val raw = resolver.openInputStream(uri)?.use { input ->
            input.readBytes(MAX_SOURCE_BYTES + 1)
        } ?: throw IllegalArgumentException("The selected image could not be opened.")
        return prepare(raw, mime)
    }

    fun fromFile(path: String): PreparedImage {
        val file = File(path)
        require(file.isFile) { "The captured screen file is missing." }
        require(file.length() <= MAX_SOURCE_BYTES) {
            "The captured screen frame is too large."
        }
        return prepare(file.readBytes(), "image/jpeg")
    }

    private fun prepare(raw: ByteArray, mimeType: String): PreparedImage {
        require(raw.isNotEmpty()) { "The image was empty." }
        require(raw.size <= MAX_SOURCE_BYTES) {
            "The image is too large. Choose an image below 16 MB."
        }

        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalArgumentException("The selected file is not a readable image.")
        val largest = max(bitmap.width, bitmap.height)
        val scaled = if (largest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toDouble() / largest.toDouble()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_OUTPUT_BYTES) {
            "The prepared image is still too large for analysis."
        }
        return PreparedImage(bytes = bytes, mimeType = "image/jpeg")
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 7 * 1024 * 1024
    private const val MAX_DIMENSION = 1_536
}
