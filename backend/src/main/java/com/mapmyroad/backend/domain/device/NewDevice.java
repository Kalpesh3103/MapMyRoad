package com.mapmyroad.backend.domain.device;

/** Values stored for a freshly registered install. No personal data (DATABASE_SCHEMA.md section A4). */
public record NewDevice(String appVersion, int osApiLevel, double trustScore) {}
