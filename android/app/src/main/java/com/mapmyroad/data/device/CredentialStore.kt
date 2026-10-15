package com.mapmyroad.data.device

/**
 * Persistent, encrypted storage for [DeviceCredentials] (SECURITY.md section 6.2). Thread-safe. Reads are served
 * from memory after the first load. Implementations never log values.
 */
interface CredentialStore {
    /** The stored credentials, or null when this install is not registered. */
    suspend fun get(): DeviceCredentials?

    suspend fun save(credentials: DeviceCredentials)

    /**
     * Atomically replaces the stored value with [transform] of it (null clears). Lets a refresh store its result
     * only if nobody cleared or replaced the credentials while the request was in flight.
     */
    suspend fun update(transform: (DeviceCredentials?) -> DeviceCredentials?)

    /** Deletes the stored credentials (and the encryption key, so the next registration starts fresh). */
    suspend fun clear()
}
