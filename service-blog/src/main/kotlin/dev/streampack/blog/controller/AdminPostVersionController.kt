/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.media.MediaStorage
import dev.streampack.blog.model.PostVersionView
import dev.streampack.blog.service.PostVersionService
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Admins attach, replace and remove a post's versions (#196): for now, a recording of the post
 * (`kind` `audio`). An upload replaces the version the post had, at the same address. The file's
 * type is checked by its first bytes (MP3, MP4/M4A, Ogg Vorbis or Opus), not only the type the
 * client sends, and its size against `STREAMPACK_MEDIA_MAX_SIZE`. The duration is read from the
 * file's headers when the uploader doesn't give it.
 *
 * While media isn't configured (`STREAMPACK_MEDIA_DIR`), uploads answer 503.
 */
@RestController
@RequestMapping("/admin/posts/{id}/versions")
@Tag(name = "Admin - Post Versions")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(
    responseCode = "401",
    description = "Not authenticated",
    content = [Content(schema = Schema(implementation = ProblemDetail::class))],
)
@ApiResponse(
    responseCode = "403",
    description = "Admin access required",
    content = [Content(schema = Schema(implementation = ProblemDetail::class))],
)
@ApiResponse(
    responseCode = "404",
    description = "No such post, or it's deleted; or, to delete, it has no such version",
    content = [Content(schema = Schema(implementation = ProblemDetail::class))],
)
class AdminPostVersionController(
    private val postVersionService: PostVersionService,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

    @Operation(
        summary = "Attach or replace a post's version",
        description =
            "Uploads a post's recording (`kind` `audio`, the only kind accepted) as " +
                "multipart/form-data: `file`, and optionally `duration` (whole seconds; read from " +
                "the file when left out) and `note` (up to 500 characters, such as \"read by the " +
                "author\"). The file must be MP3 (`audio/mpeg`), MP4/M4A (`audio/mp4`), Ogg " +
                "Vorbis (`audio/ogg`) or Ogg Opus (`audio/ogg`, `audio/opus`); its first bytes " +
                "are checked, and a declared type that disagrees is refused. A version the post " +
                "had is replaced at the same address. Answers the version as the post API shows it.",
        operationId = "putPostVersion",
    )
    @ApiResponse(
        responseCode = "201",
        description = "Attached",
        content = [Content(schema = Schema(implementation = PostVersionView::class))],
    )
    @ApiResponse(
        responseCode = "200",
        description = "Replaced",
        content = [Content(schema = Schema(implementation = PostVersionView::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A kind other than audio, an empty file, or a bad duration or note",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "Another upload of the same version was being stored",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "413",
        description = "The file is larger than the limit",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "415",
        description = "Not audio of an accepted type, or not the type it was sent as",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "503",
        description = "Media storage isn't configured",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/{kind}",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = ["application/json"],
    )
    fun upload(
        @Parameter(description = "Post UUIDv7") @PathVariable id: UUID,
        @Parameter(description = "The version's kind", example = "audio")
        @PathVariable
        kind: String,
        @RequestPart("file") file: MultipartFile,
        @Parameter(description = "Length in whole seconds; read from the file when left out")
        @RequestParam(required = false)
        duration: Int?,
        @Parameter(description = "A note, such as \"read by the author\"")
        @RequestParam(required = false)
        note: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            val attached =
                file.inputStream.use { content ->
                    postVersionService.attach(
                        id,
                        kind,
                        PostVersionService.Upload(
                            content = content,
                            declaredType = file.contentType,
                            declaredSize = file.size,
                            durationSeconds = duration,
                            note = note,
                        ),
                        admin,
                    )
                }
            ResponseEntity.status(if (attached.replaced) HttpStatus.OK else HttpStatus.CREATED)
                .body(attached.version)
        }

    @Operation(
        summary = "Remove a post's version",
        description = "Removes the post's version of this kind and its file.",
        operationId = "deletePostVersion",
    )
    @ApiResponse(responseCode = "204", description = "Removed")
    @DeleteMapping("/{kind}")
    fun delete(
        @Parameter(description = "Post UUIDv7") @PathVariable id: UUID,
        @Parameter(description = "The version's kind", example = "audio")
        @PathVariable
        kind: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            postVersionService.remove(id, kind)
            ResponseEntity.noContent().build<Void>()
        }

    /**
     * Runs [action] for an admin: 401 for no one, 403 for anyone else; the service's refusals
     * become 400, 404, 409, 413, 415 and 503.
     */
    private fun asAdmin(
        httpRequest: HttpServletRequest,
        action: (UserPrincipal) -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        if (user.role < Role.ADMIN) {
            return problem(HttpStatus.FORBIDDEN, "Insufficient privileges: requires ADMIN")
        }
        return try {
            action(user)
        } catch (e: PostVersionService.NotFoundException) {
            problem(HttpStatus.NOT_FOUND, e.message ?: "Not found")
        } catch (e: PostVersionService.InvalidException) {
            problem(HttpStatus.BAD_REQUEST, e.message ?: "Invalid upload")
        } catch (e: PostVersionService.UnsupportedTypeException) {
            problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.message ?: "Unsupported type")
        } catch (e: PostVersionService.ConflictException) {
            problem(HttpStatus.CONFLICT, e.message ?: "Conflict")
        } catch (e: MediaStorage.TooLargeException) {
            problem(HttpStatus.CONTENT_TOO_LARGE, e.message ?: "Too large")
        } catch (e: MediaStorage.NotConfiguredException) {
            problem(HttpStatus.SERVICE_UNAVAILABLE, e.message ?: "Media isn't configured")
        }
    }

    private fun problem(status: HttpStatus, message: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
}
