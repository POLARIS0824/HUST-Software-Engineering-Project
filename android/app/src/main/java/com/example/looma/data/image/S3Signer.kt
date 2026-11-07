package com.example.looma.data.image

import com.example.looma.data.settings.S3ImageBedConfig
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class SignedS3Put(
    val url: String,
    val headers: Map<String, String>,
)

internal object S3Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val TERMINATOR = "aws4_request"
    private const val HEX = "0123456789abcdef"

    private val amzDateFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val dateStampFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd")
        .withZone(ZoneOffset.UTC)

    data class Endpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val basePath: String,
    ) {
        val hostHeader: String get() = if (port > 0) "$host:$port" else host
    }

    fun endpoint(raw: String): Endpoint {
        val normalized = raw.trim().let {
            require(it.isNotEmpty()) { "the S3 endpoint is empty" }
            if ("://" in it) it else "https://$it"
        }
        val uri = URI(normalized)
        val host = requireNotNull(uri.host) { "the S3 endpoint has no host" }
        val scheme = uri.scheme ?: "https"
        return Endpoint(
            scheme = scheme,
            host = host,
            port = uri.port,
            basePath = uri.path.orEmpty().trimEnd('/'),
        )
    }

    fun objectUrl(config: S3ImageBedConfig, key: String): String {
        val endpoint = endpoint(config.endpoint)
        val encodedKey = encodePath(key)
        val publicBase = config.publicBaseUrl.trim().trimEnd('/')
        if (publicBase.isNotEmpty()) return "$publicBase/$encodedKey"
        val encodedBase = encodePath(endpoint.basePath)
        return if (config.pathStyle) {
            "${endpoint.scheme}://${endpoint.hostHeader}$encodedBase/${encodeSegment(config.bucket)}/$encodedKey"
        } else {
            "${endpoint.scheme}://${encodeSegment(config.bucket)}.${endpoint.hostHeader}$encodedBase/$encodedKey"
        }
    }

    fun signPut(
        config: S3ImageBedConfig,
        key: String,
        contentType: String,
        payload: ByteArray,
        now: Instant,
    ): SignedS3Put {
        val endpoint = endpoint(config.endpoint)
        val hostHeader = if (config.pathStyle) {
            endpoint.hostHeader
        } else {
            "${config.bucket}.${endpoint.hostHeader}"
        }
        val rawPath = if (config.pathStyle) {
            "${endpoint.basePath}/${config.bucket}/$key"
        } else {
            "${endpoint.basePath}/$key"
        }
        val canonicalUri = encodePath(rawPath)
        val url = "${endpoint.scheme}://$hostHeader$canonicalUri"

        val timestamp = amzDateFormatter.format(now)
        val dateStamp = dateStampFormatter.format(now)
        val payloadHash = sha256Hex(payload)
        val signedHeaderValues = sortedMapOf(
            "host" to hostHeader,
            "content-type" to contentType,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to timestamp,
        )
        if (config.publicRead) signedHeaderValues["x-amz-acl"] = "public-read"

        val canonicalHeaders = buildString {
            signedHeaderValues.forEach { (name, value) ->
                append(name).append(':').append(value).append('\n')
            }
        }
        val signedHeaders = signedHeaderValues.keys.joinToString(";")
        val canonicalRequest = buildString {
            append("PUT\n")
            append(canonicalUri).append('\n')
            append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val scope = "$dateStamp/${config.region}/$SERVICE/$TERMINATOR"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(timestamp).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }
        var signingKey = ("AWS4" + config.secretKey).toByteArray(Charsets.UTF_8)
        for (part in listOf(dateStamp, config.region, SERVICE, TERMINATOR)) {
            signingKey = hmacSha256(signingKey, part)
        }
        val signature = hex(hmacSha256(signingKey, stringToSign))
        val authorization =
            "$ALGORITHM Credential=${config.accessKey}/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        val requestHeaders = buildMap {
            put("Content-Type", contentType)
            put("x-amz-content-sha256", payloadHash)
            put("x-amz-date", timestamp)
            put("Authorization", authorization)
            if (config.publicRead) put("x-amz-acl", "public-read")
        }
        return SignedS3Put(url, requestHeaders)
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String {
        val builder = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            ) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(HEX[value shr 4].uppercaseChar())
                builder.append(HEX[value and 0x0F].uppercaseChar())
            }
        }
        return builder.toString()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }
}
