package com.example.looma.data.image

import com.example.looma.data.settings.S3ImageBedConfig
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class S3SignerIntegrationTest {

    private val endpoint: String? = System.getenv("S3_TEST_ENDPOINT")

    private fun config() = S3ImageBedConfig(
        endpoint = endpoint.orEmpty(),
        region = "us-east-1",
        bucket = "looma-test",
        accessKey = "S3RVER",
        secretKey = "S3RVER",
        pathPrefix = "images",
        pathStyle = true,
        publicRead = true,
    )

    @Test
    fun signedPutIsAccepted() {
        assumeTrue("set S3_TEST_ENDPOINT to run this test", endpoint != null)
        val payload = "sigv4 payload".toByteArray()
        val signed = S3Signer.signPut(
            config = config(),
            key = "images/test-${System.nanoTime()}.txt",
            contentType = "text/plain",
            payload = payload,
            now = Instant.now(),
        )
        val status = withPut(signed, payload)
        assertEquals(200, status)
    }

    @Test
    fun signatureMatchesAwsReferenceImplementation() {
        val payload = "sigv4 payload".toByteArray()
        val signed = S3Signer.signPut(
            config = config().copy(endpoint = "http://127.0.0.1:9000"),
            key = "images/图片.png",
            contentType = "text/plain",
            payload = payload,
            now = Instant.parse("2026-11-06T08:00:00Z"),
        )
        assertEquals(
            "http://127.0.0.1:9000/looma-test/images/%E5%9B%BE%E7%89%87.png",
            signed.url,
        )
        assertEquals(
            "0f5cb61577ffdbf09fd97d07084d7cd13a95669d139f09e7431b817509ccdda5",
            signed.headers.getValue("x-amz-content-sha256"),
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=S3RVER/20261106/us-east-1/s3/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-acl;x-amz-content-sha256;x-amz-date, " +
                "Signature=7125067a6bd38e2d80e30cb500491d4760abfa5b71ede548466b7e42e20a5cf3",
            signed.headers.getValue("Authorization"),
        )
    }

    @Test
    fun encodedKeyAndPrefixProduceExpectedObjectUrl() {
        val base = config().copy(endpoint = "https://s3.example.test")
        val signed = S3Signer.signPut(
            config = base,
            key = "images/2026 test/图片.png",
            contentType = "image/png",
            payload = byteArrayOf(1, 2, 3),
            now = Instant.parse("2026-11-06T08:00:00Z"),
        )
        assertEquals(
            "https://example.test/images/2026%20test/%E5%9B%BE%E7%89%87.png",
            S3Signer.objectUrl(
                base.copy(publicBaseUrl = "https://example.test"),
                "images/2026 test/图片.png",
            ),
        )
        assertEquals(
            "https://s3.example.test/looma-test/images/2026%20test/%E5%9B%BE%E7%89%87.png",
            S3Signer.objectUrl(base, "images/2026 test/图片.png"),
        )
        assertTrue(signed.headers.getValue("Authorization").contains("Signature="))
    }

    private fun withPut(signed: SignedS3Put, payload: ByteArray): Int {
        val connection = URL(signed.url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            signed.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(payload) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
