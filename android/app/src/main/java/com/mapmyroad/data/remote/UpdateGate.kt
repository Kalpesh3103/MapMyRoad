package com.mapmyroad.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the "update required" signal (API_SPEC.md section 6, update gate). Set by [UpdateRequiredInterceptor] on any
 * 426 and exposed through `DeviceSession.updateRequired`. A separate holder so the HTTP client does not depend on
 * the device session, which itself depends on the HTTP client. Never reset during the process: an app below the
 * minimum version stays below it until it is updated.
 */
@Singleton
class UpdateGate
    @Inject
    constructor() {
        private val mutableUpdateRequired = MutableStateFlow(false)

        val updateRequired: StateFlow<Boolean> = mutableUpdateRequired.asStateFlow()

        fun markUpdateRequired() {
            mutableUpdateRequired.value = true
        }
    }
