package com.mapmyroad.map

import kotlin.math.floor

/**
 * Splits the area around a track into grid cells small enough for `GET /v1/hazards`, whose box may be at most about
 * 0.1 by 0.1 degrees (API_SPEC.md 5.5). Only cells near the track are returned, in the order the track first comes
 * near them, so a long diagonal trip does not request the empty corners of its bounding box. Pure and deterministic.
 */
object BoundsGrid {
    private const val KEY_SHIFT = 32

    /**
     * Cells of [cellDeg] degrees, aligned to a global grid, that intersect the box of [padDeg] degrees around any
     * point. At most [maxCells]; the rest of the track is left out.
     */
    fun <T> cellsAlong(
        points: Iterable<T>,
        lat: (T) -> Double,
        lon: (T) -> Double,
        cellDeg: Double,
        padDeg: Double,
        maxCells: Int,
    ): List<GeoBounds> {
        require(cellDeg > 0.0 && padDeg >= 0.0 && padDeg < cellDeg) { "Invalid grid" }
        val seen = LinkedHashSet<Long>()
        for (point in points) {
            val pointLat = lat(point)
            val pointLon = lon(point)
            val rowMin = index(pointLat - padDeg, cellDeg)
            val rowMax = index(pointLat + padDeg, cellDeg)
            val colMin = index(pointLon - padDeg, cellDeg)
            val colMax = index(pointLon + padDeg, cellDeg)
            for (row in rowMin..rowMax) {
                for (col in colMin..colMax) {
                    if (seen.size >= maxCells && key(row, col) !in seen) continue
                    seen.add(key(row, col))
                }
            }
            if (seen.size >= maxCells) break
        }
        return seen.map { cellKey ->
            val row = (cellKey shr KEY_SHIFT).toInt()
            val col = cellKey.toInt()
            GeoBounds(
                south = row * cellDeg,
                west = col * cellDeg,
                north = (row + 1) * cellDeg,
                east = (col + 1) * cellDeg,
            )
        }
    }

    private fun index(
        value: Double,
        cellDeg: Double,
    ): Int = floor(value / cellDeg).toInt()

    private fun key(
        row: Int,
        col: Int,
    ): Long = (row.toLong() shl KEY_SHIFT) or (col.toLong() and 0xFFFF_FFFFL)
}
