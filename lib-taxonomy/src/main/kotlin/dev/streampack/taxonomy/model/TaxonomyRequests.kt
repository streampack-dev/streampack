/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.model

/** Request for blog-derived tag counts. */
data object FindBlogTagTaxonomyRequest

/** Request for blog-derived category counts. */
data object FindBlogCategoryTaxonomyRequest

/** Request for factoid-derived tag counts. */
data object FindFactoidTagTaxonomyRequest

/** Request for the aggregate taxonomy snapshot (tags, categories, and union counts). */
data object FindTaxonomySnapshotRequest
