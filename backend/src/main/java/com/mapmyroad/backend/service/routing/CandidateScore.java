package com.mapmyroad.backend.service.routing;

/** Comfort score (0 to 100, higher is smoother), the hazards behind it and the data coverage of one route. */
public record CandidateScore(int comfortScore, HazardCounts hazards, Coverage coverage) {}
