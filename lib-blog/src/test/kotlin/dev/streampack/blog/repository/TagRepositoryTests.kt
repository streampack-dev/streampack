/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class TagRepositoryTests {

    @Autowired lateinit var tagRepository: TagRepository

    @Test
    fun `save and retrieve tag`() {
        val tag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        val found = tagRepository.findById(tag.id).orElse(null)

        assertNotNull(found)
        assertEquals("kotlin", found.name)
        assertEquals("kotlin", found.slug)
    }

    @Test
    fun `findActive excludes deleted`() {
        tagRepository.save(Tag(name = "active", slug = "active"))
        tagRepository.save(Tag(name = "deleted", slug = "deleted", deleted = true))

        val active = tagRepository.findActive()
        assertEquals(1, active.size)
        assertEquals("active", active[0].name)
    }

    @Test
    fun `findBySlug finds tag`() {
        tagRepository.save(Tag(name = "spring-boot", slug = "spring-boot"))

        val found = tagRepository.findBySlug("spring-boot")
        assertNotNull(found)
        assertEquals("spring-boot", found!!.name)
    }

    @Test
    fun `findByName finds tag`() {
        tagRepository.save(Tag(name = "virtual-threads", slug = "virtual-threads"))

        val found = tagRepository.findByName("virtual-threads")
        assertNotNull(found)
        assertEquals("virtual-threads", found!!.slug)
    }

    @Test
    fun `unique constraint on name`() {
        tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        tagRepository.flush()

        assertThrows(Exception::class.java) {
            tagRepository.save(Tag(name = "kotlin", slug = "kotlin-2"))
            tagRepository.flush()
        }
    }

    @Test
    fun `unique constraint on slug`() {
        tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        tagRepository.flush()

        assertThrows(Exception::class.java) {
            tagRepository.save(Tag(name = "kotlin-lang", slug = "kotlin"))
            tagRepository.flush()
        }
    }
}
