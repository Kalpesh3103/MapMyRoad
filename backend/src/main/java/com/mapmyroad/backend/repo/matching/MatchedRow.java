package com.mapmyroad.backend.repo.matching;

import java.util.UUID;

/** Matching result for one row; {@code wayId} and {@code bucket} are null when the row could not be matched. */
public record MatchedRow(UUID id, Long wayId, Integer bucket) {
}
