package com.mapmyroad.data.device

/**
 * What registration tells the server about the app (API_SPEC.md section 5.2). Nothing here identifies the person.
 *
 * @property appVersion plain version such as `0.1.0`, the same value as the `X-App-Version` header.
 * @property osApiLevel Android API level (`Build.VERSION.SDK_INT`).
 */
data class DeviceInfo(
    val appVersion: String,
    val osApiLevel: Int,
)
