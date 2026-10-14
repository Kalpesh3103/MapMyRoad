-- DATABASE_SCHEMA.md A5. Filled by osm2pgsql (flex output) for the supported region (M4-3).
CREATE TABLE osm_way (
  way_id    BIGINT PRIMARY KEY,
  highway   TEXT NOT NULL,
  name      TEXT,
  surface   TEXT,
  geom      geometry(LineString, 4326) NOT NULL,
  length_m  REAL NOT NULL
);
CREATE INDEX osm_way_geom_gix ON osm_way USING GIST (geom);

CREATE TABLE osm_calming_point (
  node_id  BIGINT PRIMARY KEY,
  kind     TEXT NOT NULL CHECK (kind IN ('bump','hump','table','cushion','rumble_strip','level_crossing')),
  geom     geometry(Point, 4326) NOT NULL
);
CREATE INDEX osm_calming_point_geom_gix ON osm_calming_point USING GIST (geom);
