package com.mapmyroad.data.remote

import androidx.annotation.StringRes
import com.mapmyroad.R
import com.mapmyroad.domain.model.ApiError

/** A short, user-facing message for an [ApiError]. Screens may use their own wording where context helps. */
@StringRes
fun ApiError.messageRes(): Int =
    when (this) {
        ApiError.Network -> R.string.net_error_offline
        ApiError.Unauthorized -> R.string.net_error_unauthorized
        ApiError.UpdateRequired -> R.string.net_error_update_required
        ApiError.IntegrityFailed -> R.string.net_error_integrity
        is ApiError.RateLimited -> R.string.net_error_rate_limited
        ApiError.Unavailable -> R.string.net_error_unavailable
        ApiError.NotFound -> R.string.net_error_not_found
        is ApiError.Rejected -> R.string.net_error_rejected
        is ApiError.Server -> R.string.net_error_server
    }
