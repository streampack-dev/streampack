/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

/**
 * The one rule for system content (#254): a post in a category whose name starts with `_`
 * (`_pages`, `_sidebar`, `_test`) is reachable at its own address and nowhere else. Every query
 * that lists posts to readers, or sends them anywhere, says so with one of these, the post being
 * `p`: [NOT_SYSTEM] in JPQL, [NOT_SYSTEM_SQL] in native SQL.
 */
object SystemContent {
    const val NOT_SYSTEM =
        "NOT EXISTS (SELECT pc FROM PostCategory pc WHERE pc.post = p AND pc.category.name LIKE '\\_%' ESCAPE '\\')"

    const val NOT_SYSTEM_SQL =
        "NOT EXISTS (SELECT 1 FROM post_categories pc JOIN categories c ON c.id = pc.category_id WHERE pc.post_id = p.id AND c.name LIKE '\\_%' ESCAPE '\\')"
}
