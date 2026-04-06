/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.config

import dev.streampack.ai.service.AiService
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Activates AI services when enabled and a ChatModel provider is on the classpath */
@Configuration
@ConditionalOnProperty(prefix = "streampack.ai", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(AiProperties::class)
class AiAutoConfiguration {

    @Bean
    @ConditionalOnBean(ChatModel::class)
    fun aiService(chatModel: ChatModel, properties: AiProperties): AiService {
        return AiService(chatModel, properties)
    }
}
