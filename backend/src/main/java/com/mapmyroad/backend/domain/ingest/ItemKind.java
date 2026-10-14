package com.mapmyroad.backend.domain.ingest;

/** Kind of uploaded item, as reported in rejections and metric tags. */
public enum ItemKind {
    EVENT("event"),
    WINDOW("window");

    private final String wireName;

    ItemKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
