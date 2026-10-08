# The Atlas

The Atlas is a map of the site's tags. Each tag is a *place*, and the articles and factoids that
carry it are what's found there. Tags used together sit together, in *regions*, so the map shows
where the community writes, where it only defines things, and how its subjects connect. ui-pudl drew
it first (ui-pudl#184), laying it out and storing it itself; the layout now lives in `service-blog`,
served at [`GET /atlas`](../reference/blog-http-api.md#atlas), so every front end draws the same
geography.

## Why two levels

A plain force layout of a few hundred tags is a hairball: everything is tied to `java` or `ai`, so
everything is pulled into one knot. The Atlas lays the map out in two steps instead.

1. **Ties.** Two tags are tied when the same post or factoid carries both, weighted by cosine:
   `count(a,b) / sqrt(count(a)·count(b))`. A pair that always appears together weighs 1; a
   ubiquitous tag's ties to everything else weigh little, so it doesn't swallow the map.
2. **Regions.** Weighted label propagation groups the tags: each takes the label its ties weigh
   most for, until nothing changes. A group of fewer than three tags is folded into the group it's
   most tied to; one tied to nothing outside itself stays a small region of its own. Tags tied to
   nothing at all (only ever used alone) go to the **Uncharted** region, east of the map.
3. **Places within a region.** The biggest tag sits at the region's centre (the region is named
   for its two biggest tags), the rest start on a Vogel spiral and are drawn toward their ties and
   kept apart by the size of their marks.
4. **Regions on the map.** Regions are placed by how strongly they're tied, at the room they really
   take, and pushed apart until none overlap.

Everything is deterministic: visiting orders are fixed (biggest first, then by name) and nothing is
random, so the same tags and ties give the same map whatever order they arrive in.

## Why it holds still

A map is only useful if it can be learned: `java` should be where it was yesterday. So the layout is
computed once and stored (`atlas_region`, `atlas_place`), and after that:

- **A new tag** is placed beside its strongest relative, in that relative's region, on the first
  free spot facing away from the region's centre. Nothing already placed moves. A new tag tied to
  nothing goes to Uncharted.
- **A tag no longer used** (its posts deleted or retagged) is left off the map but keeps its stored
  place, so it comes back where it was.
- **A full relayout** happens only when an admin asks (`POST /admin/atlas/relayout`). Every place may
  move, so readers lose the map they've learned; it's for when the map no longer fits the site.

Over time newcomers can make a region lopsided, and region names (its two biggest tags when laid
out) can drift from what's biggest now. Both are what a relayout is for.

## What counts

The Atlas counts what the public site shows. Posts count when they are approved, due, not deleted
and not in a hidden (`_`) category, the same rules as `GET /taxonomy` and `GET /posts?tag=`.
Factoids count by their `tags` attribute, split on commas, as the taxonomy counts them. Tags are
lowercased, and tags starting with `_` are never placed. A place's counts *are* the taxonomy's, so
the two never disagree.

## What the server draws and what the front end does

The server decides geometry, so front ends agree: positions, each region's reach, each mark's
radius and label size, and where each place's pins (its newest articles and its factoids, at most
eight of each) sit around it. How it's drawn is the front end's: ui-pudl tells articles-only,
factoids-only and shared places apart by shape (disc, ring, diamond) as well as colour, zooms and
pans, and shows names by zoom.

*What's new* is the front end's too, since it depends on the reader's last visit. It compares
posts' `publishedAt` and factoids' `createdAt` and `updatedAt` with that visit, and finds their
places by their tags.

## Cost

Laying out the whole map is the expensive part (ui-pudl measured about a second and a half for 247
tags, reading through the API); it happens on the first request and on a relayout. Every other request reads the stored map. The assembled answer is
kept in memory, keyed by a validator over everything it depends on (visible posts and their tags,
factoid tags and times, slugs, and the stored map), so a request with nothing changed costs a few
aggregate queries, and a client holding the `ETag` gets a `304`.
