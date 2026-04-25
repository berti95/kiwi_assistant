package com.kiwi.assistant.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the JSON returned by the backend's GET /api/version. */
@Serializable
data class VersionInfo(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("apk_url") val apkUrl: String = "",
)
