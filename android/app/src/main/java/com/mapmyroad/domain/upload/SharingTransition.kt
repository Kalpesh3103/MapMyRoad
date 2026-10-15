package com.mapmyroad.domain.upload

/** The two settings that drive upload scheduling. */
data class SharingSettings(
    val contributionEnabled: Boolean,
    val uploadOnMobileData: Boolean,
)

/** What the scheduler does when [SharingSettings] change. */
enum class SchedulerCommand {
    /** Contribution is off: cancel queued uploads. */
    CANCEL,

    /** Enqueue unless a job already exists (app start, or contribution just turned on). */
    SCHEDULE,

    /** Replace the job so it gets the new network constraint. */
    RESCHEDULE,

    /** Nothing changed that matters. */
    NONE,
}

/** Pure: [previous] is null for the first value after app start. */
fun schedulerCommandFor(
    previous: SharingSettings?,
    current: SharingSettings,
): SchedulerCommand =
    when {
        !current.contributionEnabled -> SchedulerCommand.CANCEL
        previous == null || !previous.contributionEnabled -> SchedulerCommand.SCHEDULE
        previous.uploadOnMobileData != current.uploadOnMobileData -> SchedulerCommand.RESCHEDULE
        else -> SchedulerCommand.NONE
    }
