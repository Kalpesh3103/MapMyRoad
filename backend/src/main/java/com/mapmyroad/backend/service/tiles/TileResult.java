package com.mapmyroad.backend.service.tiles;

/**
 * An encoded tile. {@code bytes} is empty when no layer has a feature (the controller answers 204 then).
 * {@code etag} is null for empty tiles.
 */
public record TileResult(byte[] bytes, String etag) {

    public boolean isEmpty() {
        return bytes.length == 0;
    }

    static TileResult of(byte[] bytes) {
        return new TileResult(bytes, bytes.length == 0 ? null : TileEtag.of(bytes));
    }
}
