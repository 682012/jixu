package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 懒发现网关（[compactMcpTools]）的行为边界。
 *
 * 关注三件事：什么时候瘦身、瘦身之后模型还能不能准确找到并调用外部工具、以及网关会不会把
 * 未知/被禁用的工具变成可调用项（权限不能被名字放大）。
 */
class CompactMcpToolsTest {

    // ---------- 构造工具 ----------

    private fun stringProperty(description: String = ""): JsonObject = buildJsonObject {
        put("type", "string")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun pathSchema(): InputSchema = InputSchema.Obj(
        properties = buildJsonObject { put("path", stringProperty("path of the file")) },
        required = listOf("path"),
    )

    private fun mcpTool(
        server: String,
        name: String,
        description: String = "Tool $name exposed by $server",
        schema: InputSchema? = pathSchema(),
        needsApproval: (JsonElement) -> Boolean = { false },
        execute: suspend (JsonElement) -> List<UIMessagePart> = {
            listOf(UIMessagePart.Text("executed $name"))
        },
    ): Tool = Tool(
        name = "mcp__${server}__$name",
        description = description,
        parameters = { schema },
        needsApproval = needsApproval,
        execute = execute,
    )

    private fun builtinTool(name: String, output: String = "builtin $name"): Tool = Tool(
        name = name,
        description = "builtin tool $name",
        execute = { listOf(UIMessagePart.Text(output)) },
    )

    /** 自定义工具 + 补齐到触发阈值的填充工具。 */
    private fun catalogWith(vararg extra: Tool, fill: Int = 15, server: String = "files"): List<Tool> {
        val filler = (1..fill).map { mcpTool(server = server, name = "filler_$it") }
        return extra.toList() + filler
    }

    private fun largeCatalog(count: Int, server: String = "files"): List<Tool> =
        (1..count).map { mcpTool(server = server, name = "tool_$it") }

    private fun searchTool(tools: List<Tool>): Tool =
        compactMcpTools(tools).single { it.name == MCP_SEARCH_TOOL_NAME }

    private fun callTool(tools: List<Tool>): Tool =
        compactMcpTools(tools).single { it.name == MCP_CALL_TOOL_NAME }

    // ---------- 读结果 ----------

    private fun partsText(parts: List<UIMessagePart>): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun runSearch(tool: Tool, input: JsonObject): JsonObject =
        Json.parseToJsonElement(partsText(runBlocking { tool.execute(input) })).jsonObject

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()

    private fun JsonObject.tools(): JsonArray = this["tools"]!!.jsonArray

    private fun JsonObject.toolNames(): List<String> = tools().map { it.jsonObject.str("name") }

    private fun JsonObject.toolObjects(): List<JsonObject> = tools().map { it.jsonObject }

    private fun callInput(name: String, arguments: JsonObject? = null): JsonObject = buildJsonObject {
        put("name", name)
        if (arguments != null) put("arguments", arguments)
    }

    // ---------- 触发条件 ----------

    @Test
    fun `small catalog keeps native tools untouched`() {
        val tools = listOf(
            builtinTool("search_web"),
            mcpTool("files", "read_file"),
            mcpTool("git", "status"),
        )

        val result = compactMcpTools(tools)

        // 原生少量工具完全不变：同一个实例、同样的顺序、同样的 schema 供给方式
        assertSame(tools, result)
        assertEquals(listOf("search_web", "mcp__files__read_file", "mcp__git__status"), result.map { it.name })
        assertFalse(result.any { it.name == MCP_SEARCH_TOOL_NAME || it.name == MCP_CALL_TOOL_NAME })
    }

    @Test
    fun `no mcp tools means no gateway`() {
        val tools = listOf(builtinTool("search_web"), builtinTool("workspace_shell"))

        assertSame(tools, compactMcpTools(tools))
    }

    @Test
    fun `sixteen external tools are enough to compact`() {
        val tools = largeCatalog(16)

        assertTrue(shouldCompactMcpTools(tools))
        val result = compactMcpTools(tools)
        assertEquals(listOf(MCP_SEARCH_TOOL_NAME, MCP_CALL_TOOL_NAME), result.map { it.name })
        assertTrue(result.none { it.name.startsWith(MCP_TOOL_NAME_PREFIX) })
    }

    @Test
    fun `fifteen small external tools stay native`() {
        val tools = largeCatalog(15)

        assertFalse(shouldCompactMcpTools(tools))
        assertSame(tools, compactMcpTools(tools))
    }

    @Test
    fun `many huge schemas compact without reaching the count threshold`() {
        val bigDescription = "x".repeat(4_500)
        val tools = (1..6).map { mcpTool("files", "tool_$it", description = bigDescription) }

        assertTrue(tools.size < MCP_COMPACT_TOOL_THRESHOLD)
        assertTrue(shouldCompactMcpTools(tools))
        assertEquals(
            listOf(MCP_SEARCH_TOOL_NAME, MCP_CALL_TOOL_NAME),
            compactMcpTools(tools).map { it.name },
        )
    }

    @Test
    fun `builtin tools survive compaction in order and keep their identity`() {
        val searchWeb = builtinTool("search_web")
        val shell = builtinTool("workspace_shell")
        val tools = listOf(searchWeb, mcpTool("files", "read_file")) + largeCatalog(15) + listOf(shell)

        val result = compactMcpTools(tools)

        assertEquals(
            listOf("search_web", MCP_SEARCH_TOOL_NAME, MCP_CALL_TOOL_NAME, "workspace_shell"),
            result.map { it.name },
        )
        assertSame(searchWeb, result[0])
        assertSame(shell, result.last())
    }

    @Test
    fun `search description exposes the server set`() {
        val tools = largeCatalog(8, server = "files") + largeCatalog(8, server = "git")

        val description = searchTool(tools).description

        assertTrue(description.contains("16"))
        assertTrue(description.contains("files"))
        assertTrue(description.contains("git"))
    }

    // ---------- 检索 ----------

    @Test
    fun `exact name lookup returns the real tool with its schema`() {
        val tools = catalogWith(mcpTool("git", "status", description = "Show git status"), fill = 15)

        val json = runSearch(searchTool(tools), buildJsonObject { put("name", "mcp__git__status") })

        assertEquals(1, json.int("total"))
        assertEquals(0, json.int("offset"))
        assertTrue(json["nextOffset"] is JsonNull)
        val entry = json.toolObjects().single()
        assertEquals("mcp__git__status", entry.str("name"))
        assertEquals("git", entry.str("server"))
        assertEquals("Show git status", entry.str("description"))
        val schema = entry["inputSchema"]!!.jsonObject
        assertEquals("object", schema.str("type"))
        assertEquals(listOf("path"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(schema["properties"]!!.jsonObject.containsKey("path"))
    }

    @Test
    fun `unambiguous short name resolves to the full proxy name`() {
        val tools = catalogWith(mcpTool("git", "status"), fill = 15)

        val json = runSearch(searchTool(tools), buildJsonObject { put("name", "status") })

        assertEquals(listOf("mcp__git__status"), json.toolNames())
    }

    @Test
    fun `query matches name description and server`() {
        val tools = catalogWith(
            mcpTool("git", "status", description = "Show working tree status"),
            mcpTool("files", "read_file", description = "Read a file"),
            fill = 14,
        )
        val search = searchTool(tools)

        // 多个关键词之间是 AND：只有同时命中 read 与 file 的工具会被返回
        assertEquals(
            listOf("mcp__files__read_file"),
            runSearch(search, buildJsonObject { put("query", "read file") }).toolNames(),
        )
        assertEquals(listOf("mcp__git__status"), runSearch(search, buildJsonObject { put("query", "STATUS") }).toolNames())
        // 服务名也是可检索关键词
        assertEquals(listOf("mcp__git__status"), runSearch(search, buildJsonObject { put("query", "git") }).toolNames())
    }

    @Test
    fun `no match returns an empty page with a hint`() {
        val tools = largeCatalog(16)
        val search = searchTool(tools)

        val json = runSearch(search, buildJsonObject { put("query", "kubernetes") })

        assertEquals(0, json.int("total"))
        assertEquals(0, json.tools().size)
        assertTrue(json["hint"]!!.jsonPrimitive.content.contains(MCP_SEARCH_TOOL_NAME))

        // 精确名字查不到时就是 0 条，不会退化成模糊匹配
        val unknown = runSearch(search, buildJsonObject { put("name", "mcp__files__nope") })
        assertEquals(0, unknown.int("total"))
        assertEquals(0, unknown.tools().size)
    }

    @Test
    fun `pagination never dumps the whole catalog`() {
        val tools = largeCatalog(20)
        val search = searchTool(tools)

        val defaultPage = runSearch(search, JsonObject(emptyMap()))
        assertEquals(MCP_SEARCH_DEFAULT_LIMIT, defaultPage.tools().size)

        val first = runSearch(search, buildJsonObject { put("limit", 8) })
        assertEquals(20, first.int("total"))
        assertEquals(8, first.tools().size)
        assertEquals(8, first.int("nextOffset"))

        val second = runSearch(search, buildJsonObject { put("offset", 8); put("limit", 8) })
        assertEquals(8, second.tools().size)
        assertEquals(16, second.int("nextOffset"))
        assertTrue(first.toolNames().intersect(second.toolNames().toSet()).isEmpty())

        val last = runSearch(search, buildJsonObject { put("offset", 16); put("limit", 8) })
        assertEquals(4, last.tools().size)
        assertTrue(last["nextOffset"] is JsonNull)

        // 超上限的 limit 被压到 8：任何一次检索都拿不到全部 20 个 schema
        val oversized = runSearch(search, buildJsonObject { put("limit", 100) })
        assertEquals(MCP_SEARCH_MAX_LIMIT, oversized.tools().size)
        assertEquals(MCP_SEARCH_MAX_LIMIT, oversized.int("limit"))
    }

    // ---------- 调用 ----------

    @Test
    fun `tool call schema asks for name and arguments object`() {
        val call = callTool(largeCatalog(16))

        val schema = call.parameters() as InputSchema.Obj

        assertEquals(listOf("name"), schema.required)
        assertEquals("string", schema.properties["name"]!!.jsonObject.str("type"))
        assertEquals("object", schema.properties["arguments"]!!.jsonObject.str("type"))
    }

    @Test
    fun `tool call forwards arguments to the target tool`() {
        var received: JsonElement? = null
        val target = mcpTool("files", "read_file", execute = {
            received = it
            listOf(UIMessagePart.Text("file body"))
        })
        val call = callTool(catalogWith(target, fill = 15))

        val output = runBlocking {
            call.execute(callInput("mcp__files__read_file", buildJsonObject { put("path", "/tmp/a.txt") }))
        }

        assertEquals("file body", partsText(output))
        // 参数原样透传：不包一层、不补默认值
        assertEquals(buildJsonObject { put("path", "/tmp/a.txt") }, received)
    }

    @Test
    fun `missing arguments fall back to an empty object`() {
        var received: JsonElement? = null
        val target = mcpTool("git", "status", execute = {
            received = it
            listOf(UIMessagePart.Text("clean"))
        })
        val call = callTool(catalogWith(target, fill = 15))

        val output = runBlocking { call.execute(callInput("mcp__git__status")) }

        assertEquals("clean", partsText(output))
        assertEquals(JsonObject(emptyMap()), received)
    }

    @Test
    fun `non object arguments are rejected before execution`() {
        var executions = 0
        val target = mcpTool("files", "read_file", execute = {
            executions++
            listOf(UIMessagePart.Text("body"))
        })
        val call = callTool(catalogWith(target, fill = 15))

        val output = runBlocking {
            call.execute(buildJsonObject {
                put("name", "mcp__files__read_file")
                put("arguments", "not-an-object")
            })
        }

        assertEquals(0, executions)
        assertTrue(partsText(output).contains("must be a JSON object"))
    }

    @Test
    fun `unknown or disabled tools are refused without executing anything`() {
        var executions = 0
        val enabled = mcpTool("files", "read_file", execute = {
            executions++
            listOf(UIMessagePart.Text("body"))
        })
        // 服务上存在但被禁用：不会进入目录，因此这里也拿不到
        val disabledName = "mcp__files__delete_all"
        val call = callTool(listOf(builtinTool("search_web")) + catalogWith(enabled, fill = 15))

        val unknown = runBlocking { call.execute(callInput(disabledName, JsonObject(emptyMap()))) }
        val builtin = runBlocking { call.execute(callInput("search_web")) }
        val blank = runBlocking { call.execute(JsonObject(emptyMap())) }

        assertEquals(0, executions)
        assertTrue(partsText(unknown).contains("not available"))
        assertTrue(partsText(builtin).contains("not available"))
        assertTrue(partsText(blank).contains("not available"))

        // 目录里的工具仍然能正常执行
        val ok = runBlocking { call.execute(callInput("mcp__files__read_file", JsonObject(emptyMap()))) }
        assertEquals("body", partsText(ok))
        assertEquals(1, executions)
    }

    // ---------- 审批 ----------

    @Test
    fun `approval is delegated to the target tool with nested arguments`() {
        val guarded = mcpTool(
            server = "files",
            name = "write_file",
            needsApproval = { args ->
                (args as? JsonObject)?.let { obj ->
                    (obj["path"] as? JsonPrimitive)?.content?.startsWith("/etc/")
                } == true
            },
        )
        val tools = catalogWith(guarded, fill = 15)
        val call = callTool(tools)
        val search = searchTool(tools)

        assertTrue(call.needsApproval(callInput("mcp__files__write_file", buildJsonObject { put("path", "/etc/passwd") })))
        assertFalse(call.needsApproval(callInput("mcp__files__write_file", buildJsonObject { put("path", "/tmp/safe.txt") })))
        // 目录外的名字不能借道网关绕过审批
        assertTrue(call.needsApproval(callInput("mcp__files__drop_database")))
        // 参数形状坏掉时同样不能自动放行
        assertTrue(
            call.needsApproval(buildJsonObject {
                put("name", "mcp__files__write_file")
                put("arguments", "x")
            })
        )
        // 检索是纯读操作，永远不需要审批
        assertFalse(search.needsApproval(JsonObject(emptyMap())))
    }

    // ---------- 稳定性 ----------

    @Test
    fun `proxy names never collide with existing tools`() {
        val existingSearch = builtinTool("tool_search")
        val existingCall = builtinTool("tool_call_2")
        val tools = listOf(existingSearch, existingCall) + largeCatalog(16)

        val result = compactMcpTools(tools)
        val names = result.map { it.name }

        assertEquals(names.size, names.toSet().size)
        assertTrue(names.contains(MCP_SEARCH_TOOL_NAME))
        assertTrue(names.contains("${MCP_SEARCH_TOOL_NAME}_2"))
        assertTrue(names.contains(MCP_CALL_TOOL_NAME))
        // 原本就叫 tool_search 的内置工具没有被顶掉
        assertSame(existingSearch, result.single { it.name == MCP_SEARCH_TOOL_NAME })
        assertSame(existingCall, result.single { it.name == "tool_call_2" })
    }

    @Test
    fun `duplicate mcp names collapse into one catalog entry`() {
        val duplicated = mcpTool("files", "read_file")
        val tools = listOf(duplicated, duplicated) + largeCatalog(16)

        val json = runSearch(searchTool(tools), buildJsonObject { put("query", "read_file") })

        assertEquals(1, json.int("total"))
        assertEquals(listOf("mcp__files__read_file"), json.toolNames())
    }

    @Test
    fun `dynamic schema is re-read and stays valid json`() {
        var version = 1
        val dynamic = Tool(
            name = "mcp__dyn__multi",
            description = "dynamic schema tool",
            parameters = {
                if (version == 1) {
                    InputSchema.Obj(
                        properties = buildJsonObject { put("first", stringProperty("first arg")) },
                        required = listOf("first"),
                    )
                } else {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("second", stringProperty("second arg"))
                            put(
                                "nested",
                                buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject { put("deep", stringProperty()) })
                                },
                            )
                        },
                        required = listOf("second"),
                    )
                }
            },
            execute = { listOf(UIMessagePart.Text("dyn")) },
        )
        val search = searchTool(catalogWith(dynamic, fill = 15))

        val before = runSearch(search, buildJsonObject { put("name", "mcp__dyn__multi") }).toolObjects().single()
        assertEquals(
            listOf("first"),
            before["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        version = 2
        val after = runSearch(search, buildJsonObject { put("name", "mcp__dyn__multi") }).toolObjects().single()
        val schema = after["inputSchema"]!!.jsonObject
        assertEquals(listOf("second"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 嵌套 schema 原样保留，没有被压成字符串或被截断
        val nested = schema["properties"]!!.jsonObject["nested"]!!.jsonObject
        assertEquals("object", nested.str("type"))
        assertTrue(nested["properties"]!!.jsonObject.containsKey("deep"))
    }
}
