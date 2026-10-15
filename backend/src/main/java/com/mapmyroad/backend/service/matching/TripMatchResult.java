package com.mapmyroad.backend.service.matching;

/** Row counts for one matched trip. "Unmatched" rows are still marked processed, with no way. */
public record TripMatchResult(int eventsMatched, int eventsUnmatched, int windowsMatched, int windowsUnmatched) {

    public static final TripMatchResult EMPTY = new TripMatchResult(0, 0, 0, 0);

    public int total() {
        return eventsMatched + eventsUnmatched + windowsMatched + windowsUnmatched;
    }
}
