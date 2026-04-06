/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Category
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class CategoryRepositoryTests {

    @Autowired lateinit var categoryRepository: CategoryRepository

    @Test
    fun `save and retrieve category`() {
        val category = categoryRepository.save(Category(name = "Kotlin", slug = "kotlin"))
        val found = categoryRepository.findById(category.id).orElse(null)

        assertNotNull(found)
        assertEquals("Kotlin", found.name)
        assertEquals("kotlin", found.slug)
    }

    @Test
    fun `findActive excludes deleted`() {
        val baselineCount = categoryRepository.findActive().size
        categoryRepository.save(Category(name = "Active", slug = "active"))
        categoryRepository.save(Category(name = "Deleted", slug = "deleted", deleted = true))

        val active = categoryRepository.findActive()
        assertEquals(baselineCount + 1, active.size)
    }

    @Test
    fun `findRoots returns only parentless categories`() {
        val baselineCount = categoryRepository.findRoots().size
        val parent = categoryRepository.save(Category(name = "JVM", slug = "jvm"))
        categoryRepository.save(Category(name = "Kotlin", slug = "kotlin", parent = parent))

        val roots = categoryRepository.findRoots()
        assertEquals(baselineCount + 1, roots.size)
    }

    @Test
    fun `findChildren returns child categories`() {
        val parent = categoryRepository.save(Category(name = "JVM", slug = "jvm"))
        categoryRepository.save(Category(name = "Kotlin", slug = "kotlin", parent = parent))
        categoryRepository.save(Category(name = "Java", slug = "java", parent = parent))

        val children = categoryRepository.findChildren(parent.id)
        assertEquals(2, children.size)
    }

    @Test
    fun `findBySlug finds category`() {
        categoryRepository.save(Category(name = "Spring", slug = "spring"))

        val found = categoryRepository.findBySlug("spring")
        assertNotNull(found)
        assertEquals("Spring", found!!.name)
    }

    @Test
    fun `unique constraint on name`() {
        categoryRepository.save(Category(name = "Kotlin", slug = "kotlin"))
        categoryRepository.flush()

        assertThrows(Exception::class.java) {
            categoryRepository.save(Category(name = "Kotlin", slug = "kotlin-2"))
            categoryRepository.flush()
        }
    }

    @Test
    fun `unique constraint on slug`() {
        categoryRepository.save(Category(name = "Kotlin", slug = "kotlin"))
        categoryRepository.flush()

        assertThrows(Exception::class.java) {
            categoryRepository.save(Category(name = "Kotlin Lang", slug = "kotlin"))
            categoryRepository.flush()
        }
    }

    @Test
    fun `hierarchical parent-child relationship`() {
        val baselineRoots = categoryRepository.findRoots().size
        val grandparent =
            categoryRepository.save(Category(name = "Programming", slug = "programming"))
        val parent =
            categoryRepository.save(Category(name = "JVM", slug = "jvm", parent = grandparent))
        categoryRepository.save(Category(name = "Kotlin", slug = "kotlin", parent = parent))

        val roots = categoryRepository.findRoots()
        assertEquals(baselineRoots + 1, roots.size)

        val jvmChildren = categoryRepository.findChildren(grandparent.id)
        assertEquals(1, jvmChildren.size)
        assertEquals("JVM", jvmChildren[0].name)

        val kotlinChildren = categoryRepository.findChildren(parent.id)
        assertEquals(1, kotlinChildren.size)
        assertEquals("Kotlin", kotlinChildren[0].name)
    }
}
