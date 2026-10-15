-- osm2pgsql flex style for MapMyRoad (M4-3, DATABASE_SCHEMA.md A5).
--
-- Writes ONLY into the staging schema osm_import (tables osm_import.ways and osm_import.calming).
-- osm2pgsql drops and recreates these two staging tables on every --create run. It never touches the
-- Flyway-owned tables public.osm_way and public.osm_calming_point: infra/osm/swap.sql copies the staging
-- rows into them inside one transaction. The schema osm_import must exist before the run
-- (infra/scripts/osm-import.sh creates it); osm2pgsql does not create schemas.
--
-- Compatibility: osm2pgsql 1.8 (Debian bookworm) and 2.x (Debian trixie). Uses only API that exists in
-- both: osm2pgsql.define_table{name, schema, ids, columns}, process_node / process_way callbacks,
-- object.tags, object:as_point(), object:as_linestring() (geometry objects, osm2pgsql >= 1.7) and
-- table:insert() (add_row() was removed in 2.0 and is not used). Not verified by running both versions
-- in the environment that wrote this file: run infra/scripts/osm-import.sh --area dev once per version.
--
-- Licensing: the imported data is OpenStreetMap data, (c) OpenStreetMap contributors, ODbL 1.0.

local SCHEMA = 'osm_import'

-- Drivable road classes (motor vehicles). Paths, tracks, footways, cycleways, construction and
-- proposed roads are left out. highway=road is "unknown class" in OSM but usually drivable.
local DRIVABLE = {
    motorway = true, motorway_link = true,
    trunk = true, trunk_link = true,
    primary = true, primary_link = true,
    secondary = true, secondary_link = true,
    tertiary = true, tertiary_link = true,
    unclassified = true,
    residential = true,
    living_street = true,
    service = true,
    road = true,
}

-- traffic_calming values that a two-wheeler feels as a bump (DATABASE_SCHEMA.md A5 CHECK constraint).
-- Used to suppress hazards that sit on known speed breakers.
local CALMING_KIND = {
    bump = 'bump',
    hump = 'hump',
    table = 'table',
    cushion = 'cushion',
    rumble_strip = 'rumble_strip',
    -- "yes" means "some traffic calming, type unknown". In India an untyped traffic_calming node is
    -- almost always a speed breaker, and a wrong "bump" only suppresses a hazard report at that spot
    -- (the safe direction for false alarms). Mapped to bump. Revisit with field data.
    yes = 'bump',
    -- Deliberately skipped: chicane, choker, island, dip, painted_table and others. They narrow or mark
    -- the road and do not produce the vertical jolt the detector reacts to.
}

local tables = {}

tables.ways = osm2pgsql.define_table({
    name = 'ways',
    schema = SCHEMA,
    ids = { type = 'way', id_column = 'way_id' },
    columns = {
        { column = 'highway', type = 'text', not_null = true },
        { column = 'name', type = 'text' },
        { column = 'surface', type = 'text' },
        -- Raw access tag. access=private is kept (Valhalla may still route over it for destinations) but
        -- stored, so public tiles and exports can exclude it (SECURITY.md section 6.4).
        { column = 'access', type = 'text' },
        { column = 'geom', type = 'linestring', projection = 4326, not_null = true },
    },
})

tables.calming = osm2pgsql.define_table({
    name = 'calming',
    schema = SCHEMA,
    ids = { type = 'node', id_column = 'node_id' },
    columns = {
        { column = 'kind', type = 'text', not_null = true },
        { column = 'geom', type = 'point', projection = 4326, not_null = true },
    },
})

function osm2pgsql.process_node(object)
    local tags = object.tags
    local kind = nil
    if tags.traffic_calming then
        kind = CALMING_KIND[tags.traffic_calming]
    end
    if kind == nil and tags.railway == 'level_crossing' then
        -- Road and rail crossing (railway=crossing is a pedestrian crossing and is not included).
        kind = 'level_crossing'
    end
    if kind == nil then
        return
    end
    tables.calming:insert({
        kind = kind,
        geom = object:as_point(),
    })
end

-- traffic_calming tagged on ways (instead of nodes) is rare and ignored for the pilot.
function osm2pgsql.process_way(object)
    local tags = object.tags
    local highway = tags.highway
    if highway == nil or not DRIVABLE[highway] then
        return
    end
    -- Areas (pedestrian squares, parking areas drawn as highway=service + area=yes) are not roads.
    if tags.area == 'yes' then
        return
    end
    -- No legal access at all. Narrower restrictions (motor_vehicle=no, motorcar=no) are left to Valhalla.
    if tags.access == 'no' then
        return
    end
    -- as_linestring() returns a null geometry for degenerate ways (fewer than 2 distinct nodes); the
    -- not_null column then rejects the row, which is what we want.
    tables.ways:insert({
        highway = highway,
        name = tags.name,
        surface = tags.surface,
        access = tags.access,
        geom = object:as_linestring(),
    })
end
