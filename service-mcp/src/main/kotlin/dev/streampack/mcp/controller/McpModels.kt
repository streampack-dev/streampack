/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mcp.controller

/** JSON-RPC 2.0 request envelope for MCP transport. */
data class JsonRpcRequest(
    val jsonrpc: String? = null,
    val id: Any? = null,
    val method: String? = null,
    val params: Map<String, Any?>? = null,
)

/** JSON-RPC 2.0 success envelope. */
data class JsonRpcSuccess(val jsonrpc: String = "2.0", val id: Any? = null, val result: Any)

/** JSON-RPC 2.0 error envelope. */
data class JsonRpcErrorResponse(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val error: JsonRpcError,
)

data class JsonRpcError(val code: Int, val message: String)

/** MCP initialize result shape. */
data class InitializeResult(
    val protocolVersion: String,
    val serverInfo: ServerInfo,
    val capabilities: ServerCapabilities,
)

data class ServerInfo(val name: String, val version: String)

data class ServerCapabilities(val tools: ToolCapabilities)

data class ToolCapabilities(val listChanged: Boolean)

/** MCP tools/list result shape. */
data class ToolsListResult(val tools: List<ToolDefinition>)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonSchemaObject,
)

data class JsonSchemaObject(
    val type: String,
    val properties: Map<String, JsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
    val additionalProperties: Boolean = false,
)

data class JsonSchemaProperty(val type: String, val description: String, val default: Int? = null)

/** MCP tools/call request params. */
data class ToolCallParams(val name: String, val arguments: Map<String, Any?> = emptyMap())

/** MCP tools/call result shape. */
data class ToolCallResult(
    val isError: Boolean,
    val structuredContent: Map<String, Any?>? = null,
    val content: List<ToolCallContent>,
)

data class ToolCallContent(val type: String = "text", val text: String)

/** Typed argument models for individual MCP tools. */
data class SearchPostsArgs(val query: String = "", val page: Int? = null, val size: Int? = null)

data class GetPostArgs(val postRef: String = "")

data class ListFactoidsArgs(val page: Int? = null, val size: Int? = null)

data class GetFactoidArgs(val selector: String = "")

data class SearchFactoidsArgs(val query: String = "", val page: Int? = null, val size: Int? = null)
