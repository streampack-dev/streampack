/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mcp.controller

import dev.streampack.core.json.JacksonMappers
import dev.streampack.mcp.service.McpToolService
import dev.streampack.mcp.service.ToolResult
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.exc.MismatchedInputException

/** Minimal JSON-RPC MCP endpoint for read-only bytecode.news tools. */
@RestController
class McpController(private val toolService: McpToolService) {
    private val mapper = JacksonMappers.standard()
    private val protocolVersion = "2024-11-05"

    @PostMapping(
        "/mcp",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun handle(@RequestBody request: JsonRpcRequest): ResponseEntity<Any> {
        val id = request.id
        val method = request.method

        if (request.jsonrpc != "2.0" || method.isNullOrBlank()) {
            return ResponseEntity.ok(error(id, -32600, "Invalid Request"))
        }

        val response: Any =
            when (method) {
                "initialize" -> success(id, initializeResult())
                "tools/list" -> success(id, toolsListResult())
                "tools/call" -> handleToolCall(id, request.params)
                else -> error(id, -32601, "Method not found: $method")
            }
        return ResponseEntity.ok(response)
    }

    @GetMapping("/mcp", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun info(): ResponseEntity<String> {
        val message =
            """
            bytecode.news MCP endpoint

            This endpoint uses JSON-RPC 2.0 over HTTP POST.
            Send POST /mcp with a JSON body using methods:
            - initialize
            - tools/list
            - tools/call

            Example:
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """
                .trimIndent()
        return ResponseEntity.status(HttpStatus.OK).body(message)
    }

    private fun initializeResult() =
        InitializeResult(
            protocolVersion = protocolVersion,
            serverInfo = ServerInfo(name = "bytecode.news-mcp", version = "1.0"),
            capabilities = ServerCapabilities(tools = ToolCapabilities(listChanged = false)),
        )

    private fun toolsListResult() =
        ToolsListResult(
            tools =
                listOf(
                    toolDefinition(
                        name = "search_posts",
                        description = "Search approved public blog posts by query.",
                        properties =
                            mapOf(
                                "query" to schema("string", "Search query string"),
                                "page" to schema("integer", "Zero-based page index", 0),
                                "size" to schema("integer", "Page size (1-100)", 20),
                            ),
                        required = listOf("query"),
                    ),
                    toolDefinition(
                        name = "get_post",
                        description = "Get a single public post by UUID, slug path, or post URL.",
                        properties =
                            mapOf(
                                "postRef" to
                                    schema("string", "UUID, YYYY/MM/slug, or full post URL")
                            ),
                        required = listOf("postRef"),
                    ),
                    toolDefinition(
                        name = "list_factoids",
                        description = "List factoids with pagination.",
                        properties =
                            mapOf(
                                "page" to schema("integer", "Zero-based page index", 0),
                                "size" to schema("integer", "Page size (1-100)", 20),
                            ),
                    ),
                    toolDefinition(
                        name = "get_factoid",
                        description = "Get factoid detail and structured attributes by selector.",
                        properties = mapOf("selector" to schema("string", "Factoid selector")),
                        required = listOf("selector"),
                    ),
                    toolDefinition(
                        name = "search_factoids",
                        description = "Search factoids by selector text.",
                        properties =
                            mapOf(
                                "query" to schema("string", "Search query string"),
                                "page" to schema("integer", "Zero-based page index", 0),
                                "size" to schema("integer", "Page size (1-100)", 20),
                            ),
                        required = listOf("query"),
                    ),
                    toolDefinition(
                        name = "list_taxonomy",
                        description = "List aggregate blog/factoid tags and categories.",
                    ),
                    toolDefinition(
                        name = "factoid_write_reference",
                        description =
                            "Get command syntax and examples for creating factoids outside MCP.",
                    ),
                )
        )

    private fun handleToolCall(id: Any?, params: Map<String, Any?>?): Any {
        if (params == null) {
            return error(id, -32602, "Invalid params")
        }

        val toolCall =
            runCatching { mapper.convertValue(params, ToolCallParams::class.java) }
                .getOrElse {
                    return error(id, -32602, "Invalid tool call params")
                }

        if (toolCall.name.isBlank()) {
            return error(id, -32602, "Missing tool name")
        }

        val result =
            when (toolCall.name) {
                "search_posts" -> {
                    val args =
                        convertArgs<SearchPostsArgs>(toolCall.arguments) {
                            return error(id, -32602, "Invalid search_posts arguments")
                        }
                    toolService.searchPosts(
                        query = args.query,
                        page = args.page ?: 0,
                        size = args.size ?: 20,
                    )
                }
                "get_post" -> {
                    val args =
                        convertArgs<GetPostArgs>(toolCall.arguments) {
                            return error(id, -32602, "Invalid get_post arguments")
                        }
                    toolService.getPost(args.postRef)
                }
                "list_factoids" -> {
                    val args =
                        convertArgs<ListFactoidsArgs>(toolCall.arguments) {
                            return error(id, -32602, "Invalid list_factoids arguments")
                        }
                    toolService.listFactoids(page = args.page ?: 0, size = args.size ?: 20)
                }
                "get_factoid" -> {
                    val args =
                        convertArgs<GetFactoidArgs>(toolCall.arguments) {
                            return error(id, -32602, "Invalid get_factoid arguments")
                        }
                    toolService.getFactoid(args.selector)
                }
                "search_factoids" -> {
                    val args =
                        convertArgs<SearchFactoidsArgs>(toolCall.arguments) {
                            return error(id, -32602, "Invalid search_factoids arguments")
                        }
                    toolService.searchFactoids(
                        query = args.query,
                        page = args.page ?: 0,
                        size = args.size ?: 20,
                    )
                }
                "list_taxonomy" -> toolService.listTaxonomy()
                "factoid_write_reference" -> toolService.factoidWriteReference()
                else -> return error(id, -32601, "Unknown tool: ${toolCall.name}")
            }

        return success(id, encodeToolResult(result))
    }

    private inline fun <reified T> convertArgs(
        arguments: Map<String, Any?>,
        onError: (Exception) -> Nothing,
    ): T {
        return try {
            mapper.convertValue(arguments, T::class.java)
        } catch (ex: MismatchedInputException) {
            onError(ex)
        } catch (ex: IllegalArgumentException) {
            onError(ex)
        }
    }

    private fun encodeToolResult(result: ToolResult): ToolCallResult {
        return if (result.ok) {
            val structured = result.payload
            ToolCallResult(
                isError = false,
                structuredContent = structured,
                content = listOf(ToolCallContent(text = mapper.writeValueAsString(structured))),
            )
        } else {
            ToolCallResult(
                isError = true,
                structuredContent = null,
                content = listOf(ToolCallContent(text = result.error ?: "Tool failed")),
            )
        }
    }

    private fun toolDefinition(
        name: String,
        description: String,
        properties: Map<String, JsonSchemaProperty> = emptyMap(),
        required: List<String> = emptyList(),
    ) =
        ToolDefinition(
            name = name,
            description = description,
            inputSchema =
                JsonSchemaObject(
                    type = "object",
                    properties = properties,
                    required = required,
                    additionalProperties = false,
                ),
        )

    private fun schema(type: String, description: String, defaultValue: Int? = null) =
        JsonSchemaProperty(type = type, description = description, default = defaultValue)

    private fun success(id: Any?, result: Any): JsonRpcSuccess =
        JsonRpcSuccess(id = id, result = result)

    private fun error(id: Any?, code: Int, message: String): JsonRpcErrorResponse =
        JsonRpcErrorResponse(id = id, error = JsonRpcError(code = code, message = message))
}
