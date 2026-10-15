package com.mapmyroad.backend.repo.aggregation;

/** Road segment key (DATABASE_SCHEMA.md A7). */
public record SegmentKey(long wayId, int bucket) implements Comparable<SegmentKey> {

    @Override
    public int compareTo(SegmentKey other) {
        int byWay = Long.compare(wayId, other.wayId);
        return byWay != 0 ? byWay : Integer.compare(bucket, other.bucket);
    }
}
