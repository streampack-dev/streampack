/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.media.MediaProperties
import dev.streampack.blog.service.PostVersionService
import dev.streampack.core.service.JwtService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Serves post versions' media (#196), read-only, at an address that stays the same when the version
 * is replaced: `/media/posts/{postId}/{kind}`, or `…/{kind}/{variant}` for a variant.
 *
 * Returning the file as a [Resource] lets Spring answer `Range` requests (206, or 416 for a range
 * past the end) by reading only the bytes asked for, so players can seek and resume. Responses
 * carry `Content-Length`, the stored type, an ETag from the content's SHA-256 (so a replaced file
 * is fetched again, and `If-None-Match` is answered 304), and a short public cache lifetime.
 *
 * Only a published, undeleted post's media is public; an admin may also fetch an unpublished
 * post's, privately, to preview it. Anything else, and everything while media isn't configured,
 * is 404.
 */
@RestController
@Tag(name = "Media")
class MediaController(
    private val postVersionService: PostVersionService,
    private val mediaProperties: MediaProperties,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

    @Operation(
        summary = "Fetch a post version's media",
        description =
            "The bytes of a post's version, such as its recording (`kind` `audio`). Supports " +
                "`Range` requests (206 Partial Content; 416 when the range is past the end) and " +
                "`If-None-Match`. The address stays the same when the version is replaced; the " +
                "ETag changes. Public for published posts.",
        operationId = "getPostMedia",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The whole file",
        content =
            [Content(mediaType = "audio/*", schema = Schema(type = "string", format = "binary"))],
    )
    @ApiResponse(responseCode = "206", description = "The range asked for")
    @ApiResponse(responseCode = "304", description = "Not modified")
    @ApiResponse(responseCode = "404", description = "No such media, or it isn't public")
    @ApiResponse(responseCode = "416", description = "The range is past the end of the file")
    @GetMapping("/media/posts/{postId}/{kind}")
    fun media(
        @Parameter(description = "Post UUIDv7") @PathVariable postId: UUID,
        @Parameter(description = "The version's kind", example = "audio")
        @PathVariable
        kind: String,
        request: HttpServletRequest,
    ): ResponseEntity<Resource> = serve(postId, kind, null, request)

    @Operation(
        summary = "Fetch a post version's media, by variant",
        description = "As `/media/posts/{postId}/{kind}`, for a kind with several variants.",
        operationId = "getPostMediaVariant",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The whole file",
        content =
            [Content(mediaType = "audio/*", schema = Schema(type = "string", format = "binary"))],
    )
    @ApiResponse(responseCode = "404", description = "No such media, or it isn't public")
    @GetMapping("/media/posts/{postId}/{kind}/{variant}")
    fun mediaVariant(
        @Parameter(description = "Post UUIDv7") @PathVariable postId: UUID,
        @Parameter(description = "The version's kind") @PathVariable kind: String,
        @Parameter(description = "The version's variant") @PathVariable variant: String,
        request: HttpServletRequest,
    ): ResponseEntity<Resource> = serve(postId, kind, variant, request)

    private fun serve(
        postId: UUID,
        kind: String,
        variant: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Resource> {
        // Only resolve a credential for what can't be public anyway: no token parsing per range.
        val anonymous = postVersionService.servable(postId, kind, variant, null)
        val served =
            anonymous
                ?: postVersionService.servable(postId, kind, variant, resolveUser(request))
                ?: return ResponseEntity.notFound().build()
        val version = served.version
        val cache =
            if (served.public) CacheControl.maxAge(mediaProperties.cacheMaxAge).cachePublic()
            else CacheControl.noStore().cachePrivate()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(version.mediaType))
            .eTag("\"${version.contentHash.take(ETAG_LENGTH)}\"")
            .lastModified(version.replacedAt ?: version.createdAt)
            .cacheControl(cache)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .header("X-Content-Type-Options", "nosniff")
            .body(served.resource)
    }

    companion object {
        private const val ETAG_LENGTH = 32
    }
}
