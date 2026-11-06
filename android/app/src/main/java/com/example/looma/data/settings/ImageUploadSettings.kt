package com.example.looma.data.settings

enum class ImageUploadTarget {
    LocalAttachment,
    ImageBed,
}

data class S3ImageBedConfig(
    val endpoint: String = "",
    val region: String = "",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val pathPrefix: String = "",
    val publicBaseUrl: String = "",
    val pathStyle: Boolean = true,
    val publicRead: Boolean = true,
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() &&
            region.isNotBlank() &&
            bucket.isNotBlank() &&
            accessKey.isNotBlank() &&
            secretKey.isNotBlank()
}
