/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Integration tests for public category listing endpoint */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class CategoryControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var categoryRepository: CategoryRepository

    @BeforeEach
    fun setUp() {
        categoryRepository.save(Category(name = "Kotlin", slug = "kotlin"))
        categoryRepository.save(Category(name = "Java", slug = "java"))
        categoryRepository.save(Category(name = "_pages", slug = "_pages"))
        categoryRepository.save(Category(name = "_sidebar", slug = "_sidebar"))
        categoryRepository.save(Category(name = "Archived", slug = "archived", deleted = true))
    }

    @Test
    fun `GET categories returns active categories only`() {
        mockMvc.get("/categories").andExpect {
            status { isOk() }
            // should have _page, _sidebar, kotlin, java
            jsonPath("$.length()") { value(4) }
            jsonPath("$[0].name") { isNotEmpty() }
            jsonPath("$[0].slug") { isNotEmpty() }
            jsonPath("$[0].id") { isNotEmpty() }
        }
    }

    @Test
    fun `GET categories excludes deleted categories`() {
        mockMvc.get("/categories").andExpect {
            status { isOk() }
            jsonPath("$[?(@.name == 'Archived')]") { doesNotExist() }
        }
    }

    @Test
    fun `GET categories includes parent name for child categories`() {
        val parent = categoryRepository.findByName("Kotlin")!!
        categoryRepository.save(
            Category(name = "Kotlin Multiplatform", slug = "kotlin-multiplatform", parent = parent)
        )

        mockMvc.get("/categories").andExpect {
            status { isOk() }
            jsonPath("$[?(@.name == 'Kotlin Multiplatform')].parentName") { value("Kotlin") }
        }
    }
}
