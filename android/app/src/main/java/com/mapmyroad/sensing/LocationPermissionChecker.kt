package com.mapmyroad.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Location permission check, behind an interface so that recovery logic can be unit tested. */
interface LocationPermissionChecker {
    fun hasFineLocation(): Boolean
}

class AndroidLocationPermissionChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationPermissionChecker {
        override fun hasFineLocation(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
