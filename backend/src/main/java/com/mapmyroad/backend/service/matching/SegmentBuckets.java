package com.mapmyroad.backend.service.matching;

/**
 * Road segment keys (DATABASE_SCHEMA.md A6 and A7): a segment is {@code (way_id, bucket)} with
 * {@code bucket = floor(distance along the way / 50 m)}. Used by matching (to key windows) and by aggregation
 * (to cut the segment geometry), so both agree on the bucket boundaries.
 */
public final class SegmentBuckets {

    /** Bucket length along the way. Changing it re-keys every segment, so it is a constant, not a setting. */
    public static final double BUCKET_LENGTH_M = 50.0;

    private SegmentBuckets() {
    }

    /**
     * Bucket for a point at {@code fraction} (0 to 1, from {@code ST_LineLocatePoint}) along a way of
     * {@code wayLengthM}. Clamped to the last bucket that starts before the end of the way, so a point at the very
     * end of a way whose length is a multiple of 50 m does not open an empty bucket of zero length.
     */
    public static int bucketFor(double fraction, double wayLengthM) {
        if (!(wayLengthM > 0) || Double.isNaN(fraction)) {
            return 0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        int bucket = (int) Math.floor(clamped * wayLengthM / BUCKET_LENGTH_M);
        return Math.min(bucket, lastBucket(wayLengthM));
    }

    /** Index of the last bucket of a way: {@code ceil(length / 50) - 1}, at least 0. */
    public static int lastBucket(double wayLengthM) {
        if (!(wayLengthM > 0)) {
            return 0;
        }
        return Math.max(0, (int) Math.ceil(wayLengthM / BUCKET_LENGTH_M) - 1);
    }
}
