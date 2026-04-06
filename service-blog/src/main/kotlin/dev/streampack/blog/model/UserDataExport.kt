/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant

/** GDPR-compliant data export of a user's profile, posts, and comments */
data class UserDataExport(
    val profile: ProfileExport,
    val posts: List<PostExport>,
    val comments: List<CommentExport>,
)

data class ProfileExport(
    val username: String,
    val email: String,
    val displayName: String,
    val role: String,
    val createdAt: Instant,
)

data class PostExport(
    val title: String,
    val markdownSource: String,
    val status: String,
    val createdAt: Instant,
    val publishedAt: Instant?,
)

data class CommentExport(val postTitle: String, val markdownSource: String, val createdAt: Instant)
