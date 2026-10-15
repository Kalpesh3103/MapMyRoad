package com.mapmyroad.data.routing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mapmyroad.map.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot current position for search bias and route origin (UI_UX S8). Foreground only: this never asks for or
 * uses background location. Positions are never logged.
 */
interface CurrentLocationSource {
    /** True when fine or coarse location is granted (enough for a search bias). */
    fun hasAnyLocationPermission(): Boolean

    /** True when precise location is granted (needed for a route origin). */
    fun hasFineLocationPermission(): Boolean

    /** The current position, or null without permission, when location is off or no fix arrives in time. */
    suspend fun currentLocation(): GeoPoint?
}

@Singleton
class FusedCurrentLocationSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CurrentLocationSource {
        // Created here instead of injected, so this does not clash with other modules that may provide a client.
        private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

        override fun hasAnyLocationPermission(): Boolean =
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

        override fun hasFineLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

        // The permission is checked right before the call; lint cannot see that through hasAnyLocationPermission().
        @SuppressLint("MissingPermission")
        override suspend fun currentLocation(): GeoPoint? {
            if (!hasAnyLocationPermission()) return null
            val cancellation = CancellationTokenSource()
            return try {
                withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                    // Balanced power is enough for planning (about 100 m); it avoids spinning up GPS just for this.
                    val location =
                        client
                            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                            .await()
                    location?.let { GeoPoint(lat = it.latitude, lon = it.longitude) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call.
                Timber.w("Location permission missing for current location")
                null
            } catch (e: Exception) {
                Timber.w("Current location unavailable: %s", e.javaClass.simpleName)
                null
            } finally {
                cancellation.cancel()
            }
        }

        private fun isGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private companion object {
            const val LOCATION_TIMEOUT_MS = 10_000L
        }
    }
