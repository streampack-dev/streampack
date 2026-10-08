/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.model.TagActionType
import dev.streampack.taxonomy.model.TagReviewStatus
import dev.streampack.taxonomy.repository.TagAliasRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * Admin actions on the vocabulary (#140), without posts or factoids (their re-pointing is tested
 * where they live, in service-blog): each is recorded with who and when.
 */
@SpringBootTest
@Transactional
class TagCurationTests {
    @Autowired lateinit var curation: TagCuration
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var aliases: TagAliasRepository
    @Autowired lateinit var reviews: TagReviewRepository

    @BeforeEach
    fun setUp() {
        listOf("compilers", "java", "kotlin").forEach {
            tags.save(Tag(name = it, slug = vocabulary.uniqueSlug(it)))
        }
    }

    @Test
    fun `aliasing a queued tag removes its row, makes it an alias and closes its entry`() {
        vocabulary.accept("compiler", "post")
        val result = curation.alias("compiler", "compilers", "admin")
        assertEquals(listOf("compilers"), result.now)
        assertNull(tags.findByName("compiler"))
        assertEquals("compilers", aliases.findById("compiler").get().tag.name)
        val entry = reviews.findByTag("compiler")!!
        assertEquals(TagReviewStatus.ALIASED, entry.status)
        assertEquals("admin", entry.actedBy)
        assertNotNull(entry.actedAt)
        assertEquals("compilers", vocabulary.accept("compiler", "post"))
        assertEquals(TagActionType.ALIAS, curation.actions(1).single().action)
    }

    @Test
    fun `an alias's own aliases move with it`() {
        vocabulary.accept("compiler", "post")
        curation.alias("compilr", "compiler", "admin")
        curation.alias("compiler", "compilers", "admin")
        assertEquals("compilers", vocabulary.lookup("compilr"))
    }

    @Test
    fun `an alias target must be an existing tag, and not the tag itself`() {
        assertThrows(IllegalArgumentException::class.java) {
            curation.alias("compiler", "gcc", "admin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            curation.alias("java", "Java", "admin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            curation.alias("_idea", "java", "admin")
        }
    }

    @Test
    fun `removing an alias frees the name`() {
        curation.alias("jvm lang", "java", "admin")
        assertEquals("jvm lang", curation.removeAlias("JVM-Lang", "admin2").alias)
        assertEquals("jvm lang", vocabulary.lookup("jvm lang"))
        assertThrows(TagCuration.NotFoundException::class.java) {
            curation.removeAlias("jvm lang", "admin")
        }
        assertEquals("admin2", curation.actions(1).single().actor)
    }

    @Test
    fun `stoplisting and unstoplisting are recorded`() {
        assertEquals("self hosted", curation.stop("self-hosted", "admin").term)
        assertEquals(listOf("self hosted"), curation.stoplist().map { it.term })
        assertNull(vocabulary.accept("Self Hosted", "post"))
        curation.unstop("self hosted", "admin")
        assertEquals("self hosted", vocabulary.accept("Self Hosted", "post"))
        assertEquals(
            listOf(TagActionType.UNSTOP, TagActionType.STOP),
            curation.actions(10).map { it.action },
        )
        assertThrows(IllegalArgumentException::class.java) { curation.stop("_idea", "admin") }
    }

    @Test
    fun `keep and dismiss close an open entry, once`() {
        vocabulary.accept("compiler", "post")
        vocabulary.accept("java kotlin", "post")
        curation.keep("compiler", "admin")
        curation.dismiss("java kotlin", "admin")
        assertEquals(TagReviewStatus.KEPT, reviews.findByTag("compiler")!!.status)
        assertEquals(TagReviewStatus.DISMISSED, reviews.findByTag("java kotlin")!!.status)
        assertNotNull(tags.findByName("compiler"))
        assertThrows(IllegalArgumentException::class.java) { curation.keep("compiler", "admin") }
        assertThrows(TagCuration.NotFoundException::class.java) { curation.keep("java", "admin") }
        assertEquals(0, curation.queue(TagReviewStatus.OPEN, 0, 10).openCount)
        assertEquals(2, curation.queue(null, 0, 10).entries.size)
    }

    @Test
    fun `split defaults to the missing-comma hint`() {
        vocabulary.accept("java kotlin", "post")
        val result = curation.split("java kotlin", null, "admin")
        assertEquals(listOf("java", "kotlin"), result.now)
        assertNull(tags.findByName("java kotlin"))
        assertEquals(TagReviewStatus.SPLIT, reviews.findByTag("java kotlin")!!.status)
        assertThrows(IllegalArgumentException::class.java) {
            curation.split("compilers", null, "admin")
        }
    }
}
