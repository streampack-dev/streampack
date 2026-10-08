/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.entity.TagAlias
import dev.streampack.taxonomy.entity.TagStop
import dev.streampack.taxonomy.model.TagHint
import dev.streampack.taxonomy.model.TagHintKind
import dev.streampack.taxonomy.model.TagResolution
import dev.streampack.taxonomy.model.TagReviewStatus
import dev.streampack.taxonomy.repository.TagAliasRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.taxonomy.repository.TagStopRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** resolve, the create rule and lookup (#140). */
@SpringBootTest
@Transactional
class TagVocabularyTests {
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var aliases: TagAliasRepository
    @Autowired lateinit var stops: TagStopRepository
    @Autowired lateinit var reviews: TagReviewRepository

    private fun tag(name: String): Tag =
        tags.save(Tag(name = name, slug = vocabulary.uniqueSlug(name)))

    @BeforeEach
    fun setUp() {
        tag("compilers")
        tag("java")
        tag("kotlin")
        tag("new")
        val security = tag("security")
        aliases.save(TagAlias(alias = "auth", tag = security, createdBy = "admin"))
        stops.save(TagStop(term = "self hosted", createdBy = "admin"))
    }

    @Test
    fun `an alias resolves to its tag`() {
        assertEquals(TagResolution.Aliased("auth", "security"), vocabulary.resolve("Auth"))
        assertEquals("security", vocabulary.accept("#auth", "post"))
    }

    @Test
    fun `a stoplisted term is dropped, normalized first`() {
        assertEquals(TagResolution.Stopped("self hosted"), vocabulary.resolve("Self-Hosted"))
        assertNull(vocabulary.accept("self-hosted", "post"))
        assertEquals(listOf("java"), vocabulary.acceptAll(listOf("self_hosted", "java"), "post"))
    }

    @Test
    fun `a system tag passes through unchanged, never stopped, aliased or queued`() {
        stops.save(TagStop(term = "_idea", createdBy = "admin"))
        aliases.save(TagAlias(alias = "_draft", tag = tags.findByName("java")!!, createdBy = "x"))
        assertEquals(TagResolution.System("_idea"), vocabulary.resolve("_IDEA"))
        assertEquals("_draft", vocabulary.accept("_draft", "post"))
        assertEquals("_idea", vocabulary.accept("_idea", "post"))
        assertEquals(0, reviews.count())
    }

    @Test
    fun `an existing tag is canonical`() {
        assertEquals(TagResolution.Canonical("java"), vocabulary.resolve(" JAVA "))
    }

    @Test
    fun `a new tag with a plural hint is created and queued`() {
        assertEquals(
            TagResolution.New("compiler", TagHint(TagHintKind.PLURAL, listOf("compilers"))),
            vocabulary.resolve("compiler"),
        )
        assertEquals("compiler", vocabulary.accept("Compiler", "factoid"))
        assertNotNull(tags.findByName("compiler"))
        val entry = reviews.findByTag("compiler")!!
        assertEquals(TagHintKind.PLURAL, entry.hintKind)
        assertEquals(listOf("compilers"), entry.hintTags)
        assertEquals("factoid", entry.source)
        assertEquals(TagReviewStatus.OPEN, entry.status)
    }

    @Test
    fun `a new tag with a missing-comma hint is created and queued`() {
        assertEquals("java kotlin", vocabulary.accept("java-kotlin", "post"))
        val entry = reviews.findByTag("java kotlin")!!
        assertEquals(TagHintKind.MISSING_COMMA, entry.hintKind)
        assertEquals(listOf("java", "kotlin"), entry.hintTags)
    }

    @Test
    fun `news is created as written and queued against new, never folded into it`() {
        assertEquals("news", vocabulary.accept("news", "post"))
        assertNotNull(tags.findByName("news"))
        assertEquals(listOf("new"), reviews.findByTag("news")!!.hintTags)
    }

    @Test
    fun `an unknown tag with nothing doubtful is created and not queued`() {
        assertEquals("quarkus", vocabulary.accept("Quarkus", "post"))
        assertNotNull(tags.findByName("quarkus"))
        assertNull(reviews.findByTag("quarkus"))
    }

    @Test
    fun `the queue is deduplicated by tag`() {
        vocabulary.accept("compiler", "post")
        vocabulary.accept("compiler", "factoid")
        assertEquals(1, reviews.count())
        assertEquals(1, tags.findAll().count { it.name == "compiler" })
    }

    @Test
    fun `canonical, for suggestions, resolves without creating or queueing`() {
        assertEquals("security", vocabulary.canonical("auth"))
        assertNull(vocabulary.canonical("self hosted"))
        assertEquals("compiler", vocabulary.canonical("compiler"))
        assertNull(tags.findByName("compiler"))
        assertEquals(0, reviews.count())
    }

    @Test
    fun `lookup follows an alias, and keeps a stored name as it is`() {
        assertEquals("security", vocabulary.lookup("AUTH"))
        assertEquals("java", vocabulary.lookup("Java"))
        tag("load-testing")
        assertEquals("load-testing", vocabulary.lookup("load-testing"))
        tag("spring boot")
        assertEquals("spring boot", vocabulary.lookup("spring-boot"))
        assertEquals("_idea", vocabulary.lookup("_idea"))
        assertNull(vocabulary.lookup("  "))
    }
}
