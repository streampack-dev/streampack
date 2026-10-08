-- The Atlas (ui-pudl#184): a map of the site's tags, its geography computed once and kept so it can
-- be learned. Moved here from ui-pudl's own schema (its V2) so every front end draws the same map.
-- A full relayout happens only when an admin asks; otherwise a new tag is placed beside its
-- strongest relative and nothing already placed moves. A tag no longer used keeps its row (it is
-- left off the map until it's used again). Positions are world units.
CREATE TABLE atlas_region (
    id INT NOT NULL,
    name TEXT NOT NULL,
    x DOUBLE PRECISION NOT NULL,
    y DOUBLE PRECISION NOT NULL,
    CONSTRAINT atlas_region_pk PRIMARY KEY (id)
);

CREATE TABLE atlas_place (
    tag TEXT NOT NULL,
    region INT NOT NULL REFERENCES atlas_region (id),
    x DOUBLE PRECISION NOT NULL,
    y DOUBLE PRECISION NOT NULL,
    placed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT atlas_place_pk PRIMARY KEY (tag)
);
