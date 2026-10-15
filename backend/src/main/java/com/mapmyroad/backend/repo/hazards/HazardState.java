package com.mapmyroad.backend.repo.hazards;

/** Stored confidence and status of a hazard. */
public record HazardState(double confidence, String status) {}
