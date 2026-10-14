package com.mapmyroad.backend.domain.ingest;

import java.util.Optional;

/** Vehicle types with their wire names (API_SPEC.md 1.1) and database codes (DATABASE_SCHEMA.md A1). */
public enum VehicleType {
    MOTORCYCLE("motorcycle", 1),
    SCOOTER("scooter", 2),
    CAR("car", 3),
    AUTO_RICKSHAW("auto_rickshaw", 4);

    /** Regex of all wire names, used by request validation. Keep in sync with the constants. */
    public static final String WIRE_PATTERN = "motorcycle|scooter|car|auto_rickshaw";

    private final String wireName;
    private final int code;

    VehicleType(String wireName, int code) {
        this.wireName = wireName;
        this.code = code;
    }

    public String wireName() {
        return wireName;
    }

    /** Value stored in {@code vehicle_type} columns. */
    public int code() {
        return code;
    }

    /** Exact, case-sensitive match on the wire name. */
    public static Optional<VehicleType> fromWireName(String wireName) {
        for (VehicleType type : values()) {
            if (type.wireName.equals(wireName)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
