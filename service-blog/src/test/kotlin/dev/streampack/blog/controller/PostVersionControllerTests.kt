/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.service.PostVersionService
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.AudioFixtures
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.put
import tools.jackson.databind.json.JsonMapper

/**
 * Post versions end to end (#196): uploads and their checks, serving with ranges and ETags, the
 * post API's versions, and enclosures in the feeds and the podcast feed. Media is stored in a
 * temporary directory, with a 64 KB limit so a too-large file is small. Not transactional, so every
 * response is built as it would be in production, without an open session.
 */
@SpringBootTest(
    properties =
        [
            "streampack.media.max-size=64KB",
            "streampack.blog.podcast.author=The Reader",
            "streampack.blog.podcast.image=https://example.com/artwork.png",
            "streampack.blog.podcast.category=Technology/Tech News",
            "streampack.blog.podcast.owner-name=Ops",
            "streampack.blog.podcast.owner-email=ops@example.com",
        ]
)
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class PostVersionControllerTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var postVersionService: PostVersionService

    private val json = JsonMapper.builder().build()

    private lateinit var admin: User
    private lateinit var adminToken: String
    private lateinit var userToken: String
    private lateinit var spoken: Post
    private lateinit var silent: Post
    private lateinit var draft: Post
    private lateinit var spokenPath: String

    @BeforeEach
    fun setUp() {
        Files.walk(mediaDir).use { paths ->
            paths.filter { it != mediaDir }.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        admin =
            userRepository.save(
                User(
                    username = "voiceadmin",
                    email = "voiceadmin@test.com",
                    displayName = "Voice Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        adminToken = jwtService.generateToken(admin.toUserPrincipal())
        val user =
            userRepository.save(
                User(
                    username = "listener",
                    email = "listener@test.com",
                    displayName = "Listener",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        userToken = jwtService.generateToken(user.toUserPrincipal())

        val now = Instant.now()
        spoken = published("Spoken Post", "spoken-post", now.minus(1, ChronoUnit.HOURS))
        silent = published("Silent Post", "silent-post", now.minus(2, ChronoUnit.HOURS))
        draft =
            postRepository.save(
                Post(
                    title = "Draft Post",
                    markdownSource = "Not yet.",
                    renderedHtml = "<p>Not yet.</p>",
                    status = PostStatus.DRAFT,
                    author = admin,
                )
            )
        val at = spoken.publishedAt!!.atZone(ZoneOffset.UTC)
        spokenPath = "${at.year}/${"%02d".format(at.monthValue)}/spoken-post"
    }

    private fun published(title: String, slug: String, publishedAt: Instant): Post {
        val post =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = "The text of $title.",
                    renderedHtml = "<p>The text of $title.</p>",
                    excerpt = "About $title",
                    status = PostStatus.APPROVED,
                    publishedAt = publishedAt,
                    author = admin,
                )
            )
        val at = publishedAt.atZone(ZoneOffset.UTC)
        slugRepository.save(
            Slug(
                path = "${at.year}/${"%02d".format(at.monthValue)}/$slug",
                post = post,
                canonical = true,
            )
        )
        return post
    }

    private fun upload(
        postId: UUID,
        bytes: ByteArray,
        type: String? = "audio/mpeg",
        token: String? = adminToken,
        kind: String = "audio",
        params: Map<String, String> = emptyMap(),
    ): ResultActionsDsl =
        mockMvc.multipart("/admin/posts/$postId/versions/$kind") {
            file(MockMultipartFile("file", "recording", type, bytes))
            token?.let { header("Authorization", "Bearer $it") }
            params.forEach { (name, value) -> param(name, value) }
        }

    private fun mediaPath(postId: UUID) = "/media/posts/$postId/audio"

    private fun mediaUrl(postId: UUID) = "http://localhost:8080${mediaPath(postId)}"

    private fun storedFiles(): Long =
        Files.walk(mediaDir).use { paths -> paths.filter(Files::isRegularFile).count() }

    // --- Uploads ---

    @Test
    fun `an admin attaches a recording and the post lists it`() {
        val bytes = AudioFixtures.mp3(frames = 100)

        upload(spoken.id, bytes, params = mapOf("note" to "read by the author")).andExpect {
            status { isCreated() }
            jsonPath("$.kind") { value("audio") }
            jsonPath("$.variant") { doesNotExist() }
            jsonPath("$.mediaType") { value("audio/mpeg") }
            jsonPath("$.size") { value(bytes.size) }
            jsonPath("$.duration") { value(3) }
            jsonPath("$.url") { value(mediaUrl(spoken.id)) }
            jsonPath("$.note") { value("read by the author") }
            jsonPath("$.maker") { value("Voice Admin") }
            jsonPath("$.current") { value(true) }
        }

        mockMvc.get("/posts/$spokenPath").andExpect {
            status { isOk() }
            jsonPath("$.versions.length()") { value(1) }
            jsonPath("$.versions[0].kind") { value("audio") }
            jsonPath("$.versions[0].url") { value(mediaUrl(spoken.id)) }
            jsonPath("$.versions[0].size") { value(bytes.size) }
            jsonPath("$.versions[0].duration") { value(3) }
            jsonPath("$.versions[0].maker") { value("Voice Admin") }
            jsonPath("$.versions[0].current") { value(true) }
        }
        mockMvc.get("/posts/${silent.id}").andExpect {
            jsonPath("$.versions.length()") { value(0) }
        }
        assertEquals(1, storedFiles())
    }

    @Test
    fun `the uploader's duration wins over the file's`() {
        upload(spoken.id, AudioFixtures.mp3(), params = mapOf("duration" to "125")).andExpect {
            status { isCreated() }
            jsonPath("$.duration") { value(125) }
        }
    }

    @Test
    fun `a file sent without a type is typed by its bytes`() {
        upload(spoken.id, AudioFixtures.oggOpus(seconds = 5), type = "application/octet-stream")
            .andExpect {
                status { isCreated() }
                jsonPath("$.mediaType") { value("audio/ogg") }
                jsonPath("$.duration") { value(5) }
            }
        upload(spoken.id, AudioFixtures.m4a(seconds = 7), type = null).andExpect {
            status { isOk() }
            jsonPath("$.mediaType") { value("audio/mp4") }
            jsonPath("$.duration") { value(7) }
        }
    }

    @Test
    fun `only admins upload`() {
        upload(spoken.id, AudioFixtures.mp3(), token = null).andExpect {
            status { isUnauthorized() }
        }
        upload(spoken.id, AudioFixtures.mp3(), token = userToken).andExpect {
            status { isForbidden() }
        }
        mockMvc
            .delete("/admin/posts/${spoken.id}/versions/audio") {
                header("Authorization", "Bearer $userToken")
            }
            .andExpect { status { isForbidden() } }
        assertEquals(0, storedFiles())
    }

    @Test
    fun `refuses what isn't accepted audio, and keeps nothing`() {
        // Bytes that aren't audio, whatever the header says.
        upload(spoken.id, AudioFixtures.png()).andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.detail") { value(containsString("isn't audio")) }
        }
        // Audio, but not what it was sent as.
        upload(spoken.id, AudioFixtures.oggOpus(), type = "audio/mpeg").andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.detail") { value(containsString("audio/ogg")) }
        }
        // A type that isn't accepted.
        upload(spoken.id, AudioFixtures.mp3(), type = "text/plain").andExpect {
            status { isUnsupportedMediaType() }
        }
        assertEquals(0, storedFiles())
    }

    @Test
    fun `refuses bad uploads`() {
        upload(spoken.id, AudioFixtures.mp3(frames = 200)).andExpect {
            status { isEqualTo(413) }
        }
        upload(spoken.id, AudioFixtures.mp3(), kind = "video").andExpect {
            status { isBadRequest() }
        }
        upload(spoken.id, ByteArray(0)).andExpect { status { isBadRequest() } }
        upload(spoken.id, AudioFixtures.mp3(), params = mapOf("duration" to "0")).andExpect {
            status { isBadRequest() }
        }
        upload(spoken.id, AudioFixtures.mp3(), params = mapOf("note" to "x".repeat(501)))
            .andExpect {
                status { isBadRequest() }
            }
        upload(UUID.randomUUID(), AudioFixtures.mp3()).andExpect { status { isNotFound() } }
        postRepository.save(silent.copy(deleted = true))
        upload(silent.id, AudioFixtures.mp3()).andExpect { status { isNotFound() } }
        assertEquals(0, storedFiles())
    }

    @Test
    fun `replacing keeps the address and changes the size and ETag`() {
        val first = AudioFixtures.mp3(frames = 100)
        upload(spoken.id, first).andExpect { status { isCreated() } }
        val before = mockMvc.get(mediaPath(spoken.id)).andReturn().response
        val etag = before.getHeader("ETag")!!

        val second = AudioFixtures.mp3(frames = 120, fill = 1)
        upload(spoken.id, second).andExpect {
            status { isOk() }
            jsonPath("$.url") { value(mediaUrl(spoken.id)) }
            jsonPath("$.size") { value(second.size) }
            jsonPath("$.replacedAt") { exists() }
        }

        val after =
            mockMvc
                .get(mediaPath(spoken.id)) { header("If-None-Match", etag) }
                .andExpect { status { isOk() } }
                .andReturn()
                .response
        assertNotEquals(etag, after.getHeader("ETag"))
        assertEquals(second.size.toLong(), after.getHeader("Content-Length")!!.toLong())
        assertArrayEquals(second, after.contentAsByteArray)
        // The old file is gone.
        assertEquals(1, storedFiles())
    }

    @Test
    fun `an admin removes a recording and its file`() {
        upload(spoken.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }

        mockMvc
            .delete("/admin/posts/${spoken.id}/versions/audio") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNoContent() } }

        mockMvc.get(mediaPath(spoken.id)).andExpect { status { isNotFound() } }
        mockMvc.get("/posts/${spoken.id}").andExpect {
            jsonPath("$.versions.length()") { value(0) }
        }
        assertEquals(0, storedFiles())
        mockMvc
            .delete("/admin/posts/${spoken.id}/versions/audio") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
    }

    // --- Serving ---

    @Test
    fun `serves the whole file with its headers`() {
        val bytes = AudioFixtures.mp3()
        upload(spoken.id, bytes).andExpect { status { isCreated() } }

        val response =
            mockMvc
                .get(mediaPath(spoken.id))
                .andExpect {
                    status { isOk() }
                    content { contentType("audio/mpeg") }
                    header { string("Content-Length", bytes.size.toString()) }
                    header { string("Accept-Ranges", "bytes") }
                    header { string("Cache-Control", containsString("public")) }
                    header { string("Cache-Control", containsString("max-age=300")) }
                    header { exists("ETag") }
                    header { exists("Last-Modified") }
                }
                .andReturn()
                .response
        assertArrayEquals(bytes, response.contentAsByteArray)
        val etag = response.getHeader("ETag")!!

        mockMvc
            .get(mediaPath(spoken.id)) { header("If-None-Match", etag) }
            .andExpect {
                status { isNotModified() }
            }
    }

    @Test
    fun `serves ranges, and refuses ranges past the end`() {
        val bytes = AudioFixtures.mp3(fill = 3)
        upload(spoken.id, bytes).andExpect { status { isCreated() } }

        val part =
            mockMvc
                .get(mediaPath(spoken.id)) { header("Range", "bytes=100-199") }
                .andExpect {
                    status { isPartialContent() }
                    header { string("Content-Range", "bytes 100-199/${bytes.size}") }
                    header { string("Content-Length", "100") }
                    content { contentType("audio/mpeg") }
                }
                .andReturn()
                .response
        assertArrayEquals(bytes.copyOfRange(100, 200), part.contentAsByteArray)

        val tail =
            mockMvc
                .get(mediaPath(spoken.id)) { header("Range", "bytes=-10") }
                .andExpect { status { isPartialContent() } }
                .andReturn()
                .response
        assertArrayEquals(bytes.copyOfRange(bytes.size - 10, bytes.size), tail.contentAsByteArray)

        mockMvc
            .get(mediaPath(spoken.id)) { header("Range", "bytes=${bytes.size + 10}-") }
            .andExpect {
                status { isEqualTo(416) }
            }
    }

    @Test
    fun `an unpublished post's recording is served only to admins, privately`() {
        upload(draft.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }

        mockMvc.get(mediaPath(draft.id)).andExpect { status { isNotFound() } }
        mockMvc
            .get(mediaPath(draft.id)) { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isNotFound() } }
        mockMvc
            .get(mediaPath(draft.id)) { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                header { string("Cache-Control", containsString("no-store")) }
            }
    }

    @Test
    fun `a deleted post's recording isn't served`() {
        upload(spoken.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }
        postRepository.save(postRepository.findById(spoken.id).get().copy(deleted = true))

        mockMvc.get(mediaPath(spoken.id)).andExpect { status { isNotFound() } }
        mockMvc
            .get(mediaPath(spoken.id)) { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNotFound() } }
        assertFalse(
            FeedXml(mockMvc.get("/podcast.xml").andReturn().response.contentAsString)
                .text("/rss/channel")
                .contains("Spoken Post")
        )
    }

    @Test
    fun `no media for a post without one, or for an unknown kind`() {
        mockMvc.get(mediaPath(silent.id)).andExpect { status { isNotFound() } }
        mockMvc.get("/media/posts/${spoken.id}/video").andExpect { status { isNotFound() } }
        mockMvc.get("/media/posts/not-a-uuid/audio").andExpect { status { isBadRequest() } }
    }

    // --- The post API ---

    @Test
    fun `editing the post marks the recording as made from an earlier draft`() {
        upload(spoken.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }
        val body = mapOf("title" to "Spoken Post", "markdownSource" to "A revised text.")

        mockMvc
            .put("/posts/${spoken.id}") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(body)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.versions[0].current") { value(false) }
            }
        mockMvc.get("/posts/${spoken.id}").andExpect {
            jsonPath("$.versions[0].current") { value(false) }
        }

        // Re-recording it makes it current again.
        upload(spoken.id, AudioFixtures.mp3()).andExpect { jsonPath("$.current") { value(true) } }
    }

    @Test
    fun `attaching a recording changes the post's ETag`() {
        val etag = mockMvc.get("/posts/$spokenPath").andReturn().response.getHeader("ETag")!!
        mockMvc
            .get("/posts/$spokenPath") { header("If-None-Match", etag) }
            .andExpect {
                status { isNotModified() }
            }

        upload(spoken.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }

        mockMvc
            .get("/posts/$spokenPath") { header("If-None-Match", etag) }
            .andExpect {
                status { isOk() }
                jsonPath("$.versions.length()") { value(1) }
            }
    }

    // --- Feeds ---

    @Test
    fun `RSS feeds carry an enclosure on posts with a recording`() {
        val bytes = AudioFixtures.mp3()
        upload(spoken.id, bytes).andExpect { status { isCreated() } }

        for (path in listOf("/feed.xml", "/blog/rss.xml")) {
            val feed = FeedXml(mockMvc.get(path).andReturn().response.contentAsString)
            val item = "/rss/channel/item[title='Spoken Post']"
            assertEquals(mediaUrl(spoken.id), feed.text("$item/enclosure/@url"), path)
            assertEquals(bytes.size.toString(), feed.text("$item/enclosure/@length"), path)
            assertEquals("audio/mpeg", feed.text("$item/enclosure/@type"), path)
            assertEquals(0, feed.count("/rss/channel/item[title='Silent Post']/enclosure"), path)
        }
    }

    @Test
    fun `Atom feeds carry an enclosure link on posts with a recording`() {
        val bytes = AudioFixtures.mp3()
        upload(spoken.id, bytes).andExpect { status { isCreated() } }

        for (path in listOf("/feed.atom", "/blog/atom.xml")) {
            val feed = FeedXml(mockMvc.get(path).andReturn().response.contentAsString)
            val link = "/a:feed/a:entry[a:title='Spoken Post']/a:link[@rel='enclosure']"
            assertEquals(mediaUrl(spoken.id), feed.text("$link/@href"), path)
            assertEquals(bytes.size.toString(), feed.text("$link/@length"), path)
            assertEquals("audio/mpeg", feed.text("$link/@type"), path)
            assertEquals(
                0,
                feed.count("/a:feed/a:entry[a:title='Silent Post']/a:link[@rel='enclosure']"),
                path,
            )
        }
    }

    @Test
    fun `the podcast feed lists only posts with a recording, with the iTunes tags`() {
        val bytes = AudioFixtures.mp3()
        upload(spoken.id, bytes, params = mapOf("duration" to "3725")).andExpect {
            status { isCreated() }
        }
        upload(draft.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }

        val response =
            mockMvc
                .get("/podcast.xml")
                .andExpect {
                    status { isOk() }
                    content { contentTypeCompatibleWith("application/rss+xml") }
                }
                .andReturn()
                .response
        val feed = FeedXml(response.contentAsString)

        assertEquals(
            listOf("Spoken Post"),
            (1..feed.count("/rss/channel/item")).map {
                feed.text("/rss/channel/item[$it]/title")
            },
        )
        assertEquals("Nevet", feed.text("/rss/channel/title"))
        assertEquals("en-us", feed.text("/rss/channel/language"))
        assertEquals("The Reader", feed.text("/rss/channel/itunes:author"))
        assertEquals(
            "https://example.com/artwork.png",
            feed.text("/rss/channel/itunes:image/@href"),
        )
        assertEquals("Technology", feed.text("/rss/channel/itunes:category/@text"))
        assertEquals("Tech News", feed.text("/rss/channel/itunes:category/itunes:category/@text"))
        assertEquals("false", feed.text("/rss/channel/itunes:explicit"))
        assertEquals("Ops", feed.text("/rss/channel/itunes:owner/itunes:name"))
        assertEquals("ops@example.com", feed.text("/rss/channel/itunes:owner/itunes:email"))
        // Nothing empty: ROME writes keywords whether or not there are any.
        assertEquals(0, feed.count("//itunes:keywords"))
        val item = "/rss/channel/item[1]"
        assertEquals(mediaUrl(spoken.id), feed.text("$item/enclosure/@url"))
        assertEquals(bytes.size.toString(), feed.text("$item/enclosure/@length"))
        assertEquals("audio/mpeg", feed.text("$item/enclosure/@type"))
        assertEquals("01:02:05", feed.text("$item/itunes:duration"))
        assertTrue(feed.text("$item/pubDate").isNotBlank())
    }

    @Test
    fun `a purge deletes the files of the purged posts`() {
        upload(spoken.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }
        assertEquals(1, storedFiles())

        postVersionService.deleteFilesOfPostsByAuthor(admin.id)

        assertEquals(0, storedFiles())
    }

    @Test
    fun `media addresses are on the API's own origin`() {
        upload(spoken.id, AudioFixtures.mp3()).andExpect { status { isCreated() } }

        val url = URI(postVersionService.url(spoken.id, "audio", null))

        assertEquals("localhost:8080", url.authority)
        assertTrue(Path.of(url.path).startsWith("/media"))
    }

    companion object {
        private val mediaDir: Path = Files.createTempDirectory("streampack-media-tests")

        @JvmStatic
        @DynamicPropertySource
        fun mediaProperties(registry: DynamicPropertyRegistry) {
            registry.add("streampack.media.dir") { mediaDir.toString() }
        }
    }
}
