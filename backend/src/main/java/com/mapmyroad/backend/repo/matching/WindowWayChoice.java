package com.mapmyroad.backend.repo.matching;

import java.util.UUID;

/**
 * The way chosen for a window, with the way length and the window midpoint's position along the way as a fraction
 * from 0 to 1 ({@code ST_LineLocatePoint}).
 */
public record WindowWayChoice(UUID windowId, long wayId, double wayLengthM, double fraction) {
}
