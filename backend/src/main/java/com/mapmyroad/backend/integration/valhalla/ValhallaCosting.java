package com.mapmyroad.backend.integration.valhalla;

/**
 * Valhalla costing models per vehicle type (ARCHITECTURE.md section 9.1). Codes match DATABASE_SCHEMA.md A1.
 */
public enum ValhallaCosting {
    MOTORCYCLE("motorcycle"),
    MOTOR_SCOOTER("motor_scooter"),
    AUTO("auto");

    private final String value;

    ValhallaCosting(String value) {
        this.value = value;
    }

    /** The value of the Valhalla {@code costing} request field. */
    public String value() {
        return value;
    }

    /** Maps the stored vehicle type code (1 motorcycle, 2 scooter, 3 car, 4 auto-rickshaw). */
    public static ValhallaCosting forVehicleCode(int vehicleCode) {
        return switch (vehicleCode) {
            case 1 -> MOTORCYCLE;
            case 2 -> MOTOR_SCOOTER;
            case 3, 4 -> AUTO;
            default -> throw new IllegalArgumentException("Unknown vehicle type code " + vehicleCode);
        };
    }
}
