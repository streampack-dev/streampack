/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.model.CreateCategoryRequest
import dev.streampack.blog.model.CreateCategoryResponse
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class CreateCategoryOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var categoryRepository: CategoryRepository

    private lateinit var adminUser: User
    private lateinit var regularUser: User

    @BeforeEach
    fun setUp() {
        adminUser =
            userRepository.save(
                User(
                    username = "catadmin",
                    email = "catadmin@test.com",
                    displayName = "Cat Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        regularUser =
            userRepository.save(
                User(
                    username = "catuser",
                    email = "catuser@test.com",
                    displayName = "Cat User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
    }

    private fun createMessage(request: CreateCategoryRequest, user: User?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "admin/categories",
                    user =
                        user?.let {
                            UserPrincipal(
                                id = it.id,
                                username = it.username,
                                displayName = it.displayName,
                                role = it.role,
                            )
                        },
                ),
            )
            .build()

    @Test
    fun `admin creates category successfully`() {
        val request = CreateCategoryRequest("Kotlin")
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as CreateCategoryResponse
        assertEquals("Kotlin", response.name)
        assertEquals("kotlin", response.slug)
        assertNotNull(response.id)
    }

    @Test
    fun `non-admin rejected`() {
        val request = CreateCategoryRequest("Java")
        val result = eventGateway.process(createMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `blank name rejected`() {
        val request = CreateCategoryRequest("  ")
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Category name is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `duplicate name rejected`() {
        categoryRepository.save(Category(name = "Existing", slug = "existing"))

        val request = CreateCategoryRequest("Existing")
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Category name already exists", (result as OperationResult.Error).message)
    }

    @Test
    fun `create with parent category`() {
        val parent = categoryRepository.save(Category(name = "Languages", slug = "languages"))

        val request = CreateCategoryRequest("Kotlin", parentId = parent.id)
        val result = eventGateway.process(createMessage(request, adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as CreateCategoryResponse
        assertEquals("Languages", response.parentName)
    }
}
