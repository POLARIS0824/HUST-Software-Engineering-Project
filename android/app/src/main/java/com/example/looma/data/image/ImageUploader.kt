package com.example.looma.data.image

import android.content.Context
import android.net.Uri
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.ImageUploadTarget
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.data.vault.VaultRepository
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.data.note.writeBytesAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ImageUpload {
    data class Attachment(val fileName: String) : ImageUpload
    data class Remote(val url: String) : ImageUpload
}

enum class ImageUploadFailure {
    NotConfigured,
    UnsupportedImage,
    ImageTooLarge,
    ReadFailed,
    StorageFailed,
    Rejected,
    Network,
}

class ImageUploadException(
    val failure: ImageUploadFailure,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(detail, cause)

class ImageUploader(
    private val context: Context,
    private val vaultRepository: VaultRepository,
) {

    suspend fun upload(uri: Uri, settings: AppSettings): ImageUpload = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()
        val extension = extensionFor(mimeType)
            ?: throw ImageUploadException(ImageUploadFailure.UnsupportedImage)
        val bytes = readBytes(uri)
        val fileName = sha256Hex(bytes).take(16) + "." + extension
        when (settings.imageUploadTarget) {
            ImageUploadTarget.LocalAttachment -> saveAttachment(fileName, bytes)
            ImageUploadTarget.ImageBed -> uploadToImageBed(settings.imageBed, fileName, mimeType, bytes)
        }
    }

    private suspend fun saveAttachment(fileName: String, bytes: ByteArray): ImageUpload {
        val vault = vaultRepository.requireCurrentVault()
        return VaultWriteCoordinator.write(vault) {
            val root = File(vault.rootPath).canonicalFile
            val file = File(root, fileName).canonicalFile
            if (!file.toPath().startsWith(root.toPath())) {
                throw ImageUploadException(ImageUploadFailure.StorageFailed)
            }
            if (!file.isFile) {
                runCatching { writeBytesAtomically(file, bytes) }
                    .onFailure { throw ImageUploadException(ImageUploadFailure.StorageFailed, it.message, it) }
            }
            ImageUpload.Attachment(fileName)
        }
    }

    private fun uploadToImageBed(
        config: S3ImageBedConfig,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ImageUpload {
        if (!config.isConfigured) throw ImageUploadException(ImageUploadFailure.NotConfigured)
        val key = listOf(config.pathPrefix.trim().trim('/'), fileName)
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val signed = runCatching { S3Signer.signPut(config, key, mimeType, bytes, Instant.now()) }
            .getOrElse { throw ImageUploadException(ImageUploadFailure.NotConfigured, it.message, it) }
        put(signed, bytes)
        return ImageUpload.Remote(S3Signer.objectUrl(config, key))
    }

    private fun put(signed: SignedS3Put, bytes: ByteArray) {
        val connection = try {
            URL(signed.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ImageUploadException(ImageUploadFailure.NotConfigured, e.message, e)
        }
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            signed.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = (connection.errorStream ?: connection.inputStream)
                    ?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
                    .orEmpty()
                    .take(300)
                throw ImageUploadException(
                    ImageUploadFailure.Rejected,
                    "HTTP $status $body".trim(),
                )
            }
        } catch (e: ImageUploadException) {
            throw e
        } catch (e: IOException) {
            throw ImageUploadException(ImageUploadFailure.Network, e.message, e)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw ImageUploadException(ImageUploadFailure.ReadFailed, e.message, e)
        } ?: throw ImageUploadException(ImageUploadFailure.ReadFailed)
        return stream.use {
            val buffer = ByteArray(16 * 1024)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) {
                    throw ImageUploadException(ImageUploadFailure.ImageTooLarge)
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun extensionFor(mimeType: String): String? = when (mimeType) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        else -> null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
