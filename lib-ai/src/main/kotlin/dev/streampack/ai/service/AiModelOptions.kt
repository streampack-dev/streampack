/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.service

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * The options a prompt carries to ask for a model other than the chat model's default.
 *
 * Spring AI uses a prompt's options in place of the chat model's, not merged over them, so these
 * must be whole: the timeout, token limit and the rest, as well as the model. A provider that knows
 * which settings a model takes supplies its own; otherwise [sameSettings] copies the defaults.
 */
fun interface AiModelOptions {
    fun forModel(model: String): ChatOptions

    companion object {
        /** The chat model's own options with only the model changed, read when a call is made. */
        fun sameSettings(chatModel: ChatModel): AiModelOptions = AiModelOptions { model ->
            chatModel.options.mutate().model(model).build()
        }
    }
}
