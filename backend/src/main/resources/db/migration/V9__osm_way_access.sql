-- SECURITY.md 6.4: ways tagged access=private (or access=no) are never published in tiles or the hazards API.
-- NULL means the tag is absent. The OSM import (M4-3) fills this column from the way's `access` tag; until it
-- does, every way counts as accessible and only the device and sample thresholds apply.
ALTER TABLE osm_way ADD COLUMN access TEXT;
