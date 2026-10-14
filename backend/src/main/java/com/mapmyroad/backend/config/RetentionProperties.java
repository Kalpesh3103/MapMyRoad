package com.mapmyroad.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nightly retention job settings (DATABASE_SCHEMA.md section A10, SECURITY.md section 6.3). Changing any
 * period weakens or strengthens privacy guarantees, so changes need an ADR (AGENTS.md section 6).
 *
 * @param enabled whether the scheduled trigger runs (the job can still be run on demand)
 * @param cron Spring cron expression, default nightly at 03:30
 * @param zone time zone for the cron expression
 * @param deviceIdDays raw rows lose their device ID after this many days
 * @param tripTokenHours trip tokens are cleared after this many hours at the latest
 * @param rawRowDays raw events and observations are deleted after this many days
 * @param inactiveDeviceMonths devices not seen for this many months are deleted
 * @param batchSize rows touched per statement, so no step holds locks or a transaction for long
 */
@ConfigurationProperties(prefix = "mmr.retention")
public record RetentionProperties(
        Boolean enabled,
        String cron,
        String zone,
        Integer deviceIdDays,
        Integer tripTokenHours,
        Integer rawRowDays,
        Integer inactiveDeviceMonths,
        Integer batchSize) {

    public RetentionProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        cron = cron == null || cron.isBlank() ? "0 30 3 * * *" : cron;
        zone = zone == null || zone.isBlank() ? "Asia/Kolkata" : zone;
        deviceIdDays = deviceIdDays == null ? 90 : deviceIdDays;
        tripTokenHours = tripTokenHours == null ? 24 : tripTokenHours;
        rawRowDays = rawRowDays == null ? 365 : rawRowDays;
        inactiveDeviceMonths = inactiveDeviceMonths == null ? 12 : inactiveDeviceMonths;
        batchSize = batchSize == null ? 5000 : batchSize;
        if (deviceIdDays < 1 || tripTokenHours < 1 || rawRowDays < 1 || inactiveDeviceMonths < 1 || batchSize < 1) {
            throw new IllegalArgumentException("mmr.retention periods and batch size must be positive");
        }
    }

    public static RetentionProperties defaults() {
        return new RetentionProperties(null, null, null, null, null, null, null, null);
    }
}
