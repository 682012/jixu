package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart

/**
 * MCP 懒发现网关。
 *
 * 问题：MCP 服务一多，外部工具就会把请求挤爆——每个工具都带着完整的 inputSchema 进 prompt，
 * 一次几十个工具意味着每轮对话都为几乎不会调用的 schema 付费，还挤掉真正有用的上下文。
 *
 * 做法：外部工具（`mcp__server__tool`）数量或体积过线后，把它们的清单整体换成一个稳定的两件套
 * [MCP_SEARCH_TOOL_NAME] + [MCP_CALL_TOOL_NAME]：
 *  - `tool_search` 按名称精确查或按关键词分页检索，单次最多返回 [MCP_SEARCH_MAX_LIMIT] 条，
 *    命中项才带真实 name / description / inputSchema；
 *  - `tool_call` 用 `{ name, arguments }` 调真正的外部工具，每次审批与执行都重新按当前的启用目录校验。
 *
 * 关键约束：
 *  - 目录顺序稳定（按工具全名排序），否则每次请求的工具清单都不一样，提示词缓存全废；
 *  - 不猜外部工具是否只读，网关自身不做并发，一律保守串行；
 *  - 本文件只新增，不改 ChatToolFactory / Tool / AgentLoop：网关对外仍是一个普通 [Tool]，
 *    AgentLoop 依旧按 `toolName` 找到它、按 `toolCallId` 回填结果，`tool_name`/`id` 协议不受影响
 *    （proxy 用本名 tool_call 回填即可）。
 *
 * 集成提示：`tool_search` 是纯读的检索，后续 `Tool.executionMode` 落地后可设为
 * `PARALLEL_DEDUPLICATED`（每次结果只依赖入参与固定目录），`tool_call` 保持默认 SERIAL。
 */

/** 外部 MCP 工具在统一清单里的命名前缀，ChatToolFactory 拼成 `mcp__<server>__<tool>`。 */
internal const val MCP_TOOL_NAME_PREFIX = "mcp__"

/** 懒发现网关的检索入口名。 */
internal const val MCP_SEARCH_TOOL_NAME = "tool_search"

/** 懒发现网关的调用入口名。 */
internal const val MCP_CALL_TOOL_NAME = "tool_call"

/** 数量阈值：外部工具达到这个数量就改走网关。 */
internal const val MCP_COMPACT_TOOL_THRESHOLD = 16

/** 体积阈值：name + description + parameters 的估算字符数。 */
internal const val MCP_COMPACT_CHAR_THRESHOLD = 20_000

/** `tool_search` 单页上限，保证任何一次检索都不会把全部 schema 灌给模型。 */
internal const val MCP_SEARCH_MAX_LIMIT = 8

/** `tool_search` 默认单页条数。 */
internal const val MCP_SEARCH_DEFAULT_LIMIT = 6

/** 网关目录里的一条外部工具。 */
private data class McpCatalogEntry(
    /** 完整代理名，模型在 `tool_call` 里必须原样使用 */
    val name: String,
    /** 所属 MCP 服务名，用于描述里的服务集合与检索 */
    val server: String,
    /** 服务内的原始工具名，允许在唯一时作为简称使用 */
    val rawName: String,
    val tool: Tool,
    /** 预生成的小写检索文本：名称 + 原始名 + 服务名 + 描述 */
    val haystack: String,
)

/**
 * 还有余量时保持原样：外部工具不多、或者整体很小，直接给模型原生工具清单最省事。
 *
 * 两个阈值任一命中就瘦身：数量 >= [MCP_COMPACT_TOOL_THRESHOLD]，或估算体积 >= [MCP_COMPACT_CHAR_THRESHOLD]。
 */
internal fun shouldCompactMcpTools(mcpTools: List<Tool>): Boolean {
    if (mcpTools.isEmpty()) return false
    if (mcpTools.size >= MCP_COMPACT_TOOL_THRESHOLD) return true
    return mcpTools.sumOf { estimatedChars(it) } >= MCP_COMPACT_CHAR_THRESHOLD
}

private fun estimatedChars(tool: Tool): Int {
    val schemaChars = runCatching { tool.parameters().toJsonSchema().toString().length }.getOrDefault(0)
    return tool.name.length + tool.description.length + schemaChars
}

/**
 * [InputSchema] -> 真正发给 provider 的 JSON Schema 对象。
 *
 * provider 侧用序列化器把 `InputSchema.Obj` 编成 `{"type":"object","properties":{...},"required":[...]}`，
 * 但 `me.rerere.ai.util.json` 是 ai 模块 internal 的；网关要在搜索结果里给出同一个形状，
 * 所以在这里按同样的编码规则手写（只多不少，模型看到的是它调用时真正会校验的 schema）。
 */
internal fun InputSchema?.toJsonSchema(): JsonObject = when (this) {
    is InputSchema.Obj -> buildJsonObject {
        put("type", "object")
        put("properties", properties)
        required?.takeIf { it.isNotEmpty() }?.let { requiredNames ->
            put("required", JsonArray(requiredNames.map { JsonPrimitive(it) }))
        }
    }

    // 没有 schema 的工具（少数外部工具不声明参数）也要给一个合法对象，避免 provider 拒收
    else -> buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }
}

/** `mcp__server__tool` -> `server`；不符合前缀时原样返回去掉前缀的串。 */
private fun String.mcpServerName(): String {
    val body = removePrefix(MCP_TOOL_NAME_PREFIX)
    val index = body.indexOf("__")
    return if (index >= 0) body.substring(0, index) else body
}

/** `mcp__server__tool` -> `tool`。 */
private fun String.mcpRawToolName(): String {
    val body = removePrefix(MCP_TOOL_NAME_PREFIX)
    val index = body.indexOf("__")
    return if (index >= 0) body.substring(index + 2) else ""
}

private fun isMcpTool(tool: Tool): Boolean = tool.name.startsWith(MCP_TOOL_NAME_PREFIX)

/** 稳定目录：按工具全名排序，同名只留第一条，重名不会变成两个可调用项。 */
private fun buildCatalog(mcpTools: List<Tool>): List<McpCatalogEntry> = mcpTools
    .distinctBy { it.name }
    .map { tool ->
        val server = tool.name.mcpServerName()
        val rawName = tool.name.mcpRawToolName()
        McpCatalogEntry(
            name = tool.name,
            server = server,
            rawName = rawName,
            tool = tool,
            haystack = buildString {
                append(tool.name.lowercase())
                append('\n').append(rawName.lowercase())
                append('\n').append(server.lowercase())
                append('\n').append(tool.description.lowercase())
            },
        )
    }
    .sortedBy { it.name }

/**
 * 把外部工具清单换成一个稳定的检索 + 调用两件套。
 *
 * - 未触发阈值、或没有外部工具时，原样返回传入的列表（同一个实例，原生少量工具完全不变）；
 * - 触发后内置工具全部原样保留、相对顺序不变，外部工具整体被网关替换。
 */
internal fun compactMcpTools(tools: List<Tool>): List<Tool> {
    val mcpTools = tools.filter(::isMcpTool)
    if (!shouldCompactMcpTools(mcpTools)) return tools

    val catalog = buildCatalog(mcpTools)
    // 名字不能撞：既有工具里已经叫 tool_search / tool_call 时退化成带序号的稳定别名
    val taken: MutableSet<String> = tools.mapTo(mutableSetOf()) { it.name }
    val searchName = uniqueToolName(MCP_SEARCH_TOOL_NAME, taken).also { taken += it }
    val callName = uniqueToolName(MCP_CALL_TOOL_NAME, taken).also { taken += it }

    val proxies = listOf(
        createSearchTool(name = searchName, callName = callName, catalog = catalog),
        createCallTool(name = callName, searchName = searchName, catalog = catalog),
    )

    // 网关插在原先第一个外部工具的位置：保留段相对顺序不变，前面内置工具的 prompt 前缀也保持稳定
    val firstMcpIndex = tools.indexOfFirst(::isMcpTool).takeIf { it >= 0 } ?: tools.size
    val kept = tools.filterNot(::isMcpTool)
    val insertAt = tools.take(firstMcpIndex).count { !isMcpTool(it) }
    return kept.take(insertAt) + proxies + kept.drop(insertAt)
}

/** 在 [taken] 里挑一个不冲突的名字；冲突时用 `<base>_2`、`<base>_3`……，结果只取决于入参，稳定可复现。 */
internal fun uniqueToolName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var index = 2
    while ("${base}_$index" in taken) index++
    return "${base}_$index"
}

/**
 * 检索入口。
 *
 * 目录本身（服务集合、总数）写进 description，模型不看 schema 也知道有哪些服务可查；
 * 但每个工具的真实 schema 只在命中后返回，且单页最多 [MCP_SEARCH_MAX_LIMIT] 条。
 */
private fun createSearchTool(name: String, callName: String, catalog: List<McpCatalogEntry>): Tool {
    val servers = catalog.map { it.server }.distinct().sorted()
    return Tool(
        name = name,
        executionMode = ToolExecutionMode.PARALLEL_DEDUPLICATED,
        description = buildString {
            append("Search the catalog of external MCP tools. Searching only reads the catalog, nothing is executed.\n")
            append("Catalog: ${catalog.size} tool(s) from ${servers.size} server(s) — ${servers.joinToString(", ")}.\n")
            append("Pass `name` to fetch one tool by exact name, or `query` for case-insensitive keywords ")
            append("(every whitespace-separated term must appear in the tool name, its description or the server name).\n")
            append("Results are paged with `offset` and `limit` (max $MCP_SEARCH_MAX_LIMIT); each hit returns the real tool name, ")
            append("description and input schema, together with `total`, `offset` and `nextOffset`.\n")
            append("Then invoke a hit with `$callName`, passing exactly that `name` and an `arguments` object matching its `inputSchema`.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Case-insensitive keywords matched against tool name, description and server name. " +
                                "Omit to browse the catalog page by page."
                        )
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Exact tool name such as `mcp__files__read_file`, used to fetch a single tool with its full input schema."
                        )
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Pagination offset, default 0.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Page size, default $MCP_SEARCH_DEFAULT_LIMIT, max $MCP_SEARCH_MAX_LIMIT.")
                    })
                },
                required = emptyList(),
            )
        },
        // 纯本地目录检索：不触达外部服务，也没有副作用
        needsApproval = { false },
        execute = { input ->
            val args = input as? JsonObject ?: JsonObject(emptyMap())
            val exactName = args.stringOrNull("name")
            val query = args.stringOrNull("query")
            val offset = (args.intOrNull("offset") ?: 0).coerceAtLeast(0)
            val limit = (args.intOrNull("limit") ?: MCP_SEARCH_DEFAULT_LIMIT).coerceIn(1, MCP_SEARCH_MAX_LIMIT)
            val matched = searchCatalog(catalog, exactName = exactName, query = query)
            val page = matched.drop(offset).take(limit)
            // 还有下一页才给 nextOffset，翻到底就是 null，避免模型无限翻页
            val nextOffset = (offset + page.size).takeIf { it < matched.size }
            val payload = buildJsonObject {
                put("total", matched.size)
                put("offset", offset)
                put("limit", limit)
                put("servers", JsonArray(servers.map { JsonPrimitive(it) }))
                put("nextOffset", nextOffset?.let { JsonPrimitive(it) } ?: JsonNull)
                put("tools", JsonArray(page.map { it.toJson() }))
                if (matched.isEmpty()) put("hint", noMatchHint(name, exactName, query))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
}

/**
 * 目录检索。
 *
 * `name` 优先且只做「精确」匹配（全名，或目录里唯一的原始名）——模糊匹配会让模型以为自己找到了
 * 另一个工具；`query` 才是关键词检索，多个词之间是 AND。
 */
private fun searchCatalog(
    catalog: List<McpCatalogEntry>,
    exactName: String?,
    query: String?,
): List<McpCatalogEntry> {
    val requested = exactName?.trim()?.takeIf { it.isNotEmpty() }
    if (requested != null) {
        catalog.firstOrNull { it.name.equals(requested, ignoreCase = true) }?.let { return listOf(it) }
        // 简称只在目录里唯一时才能等价还原成全名，否则宁可不给，免得调错服务上的同名工具
        return catalog.filter { it.rawName.isNotEmpty() && it.rawName.equals(requested, ignoreCase = true) }
            .let { if (it.size == 1) it else emptyList() }
    }
    val tokens = query.orEmpty().lowercase().split(' ', '\n', '\t', '\r').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return catalog
    return catalog.filter { entry -> tokens.all { it in entry.haystack } }
}

private fun noMatchHint(searchName: String, exactName: String?, query: String?): String {
    val what = when {
        !exactName.isNullOrBlank() -> "`$exactName` is not an available tool in this conversation"
        !query.isNullOrBlank() -> "no tool matches `$query`"
        else -> "the tool catalog is empty"
    }
    return "$what. Call `$searchName` with no arguments to browse the catalog."
}

/** 单条命中：真实 name / description / inputSchema，模型据此才能拼出正确的调用参数。 */
private fun McpCatalogEntry.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("server", server)
    put("description", tool.description)
    // schema 每次现取：外部服务重连后参数定义可能变了，缓存旧 schema 会把模型引向必然失败的调用
    put("inputSchema", tool.parameters().toJsonSchema())
}

/** `tool_call` 的解析结果：审批与执行共用同一套校验，两条路径不会各自放宽。 */
private sealed interface ResolvedCall {
    data class Found(val entry: McpCatalogEntry, val arguments: JsonObject) : ResolvedCall
    data class Unknown(val requestedName: String) : ResolvedCall
    data class BadArguments(val requestedName: String) : ResolvedCall
}

/**
 * 调用入口。
 *
 * 只转交 `arguments` 给目标工具，不合并、不改写、不加默认值：目标工具看到的就是模型写的东西。
 * 审批同理——`needsApproval` 拿到的必须是内层 arguments，否则「写文件需要批准」这类判断会失效。
 */
private fun createCallTool(name: String, searchName: String, catalog: List<McpCatalogEntry>): Tool = Tool(
    name = name,
    description = buildString {
        append("Invoke one external MCP tool from the catalog.\n")
        append("Pass the exact `name` returned by `$searchName` and an `arguments` object matching that tool's `inputSchema`.\n")
        append("Tools that are not in the catalog (unknown, disabled or from a server not attached to this conversation) are rejected ")
        append("without being executed, and never inherit permissions from a similarly named tool.\n")
        append("If you have not seen the tool's schema yet, search for it with `$searchName` first.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Exact tool name as returned by `$searchName`, e.g. `mcp__files__read_file`."
                    )
                })
                put("arguments", buildJsonObject {
                    put("type", "object")
                    put(
                        "description",
                        "Arguments for the target tool, matching its `inputSchema`. Omit when the tool takes no arguments."
                    )
                    put("additionalProperties", true)
                })
            },
            required = listOf("name"),
        )
    },
    needsApproval = { input ->
        when (val call = resolveCall(catalog, input)) {
            is ResolvedCall.Found -> call.entry.tool.needsApproval(call.arguments)
            // 认不出来的调用不能自动放行：审批走保守路线，真正执行时也会被拒
            is ResolvedCall.Unknown, is ResolvedCall.BadArguments -> true
        }
    },
    execute = { input ->
        when (val call = resolveCall(catalog, input)) {
            is ResolvedCall.Found -> call.entry.tool.execute(call.arguments)
            is ResolvedCall.Unknown -> listOf(
                UIMessagePart.Text(
                    "Tool '${call.requestedName}' is not available in this conversation and was not executed. " +
                        "Only tools returned by `$searchName` can be called; search again to get an exact name."
                )
            )

            is ResolvedCall.BadArguments -> listOf(
                UIMessagePart.Text(
                    "`arguments` for '${call.requestedName}' must be a JSON object, not a string or array. " +
                        "Retry with the object shape from `$searchName`."
                )
            )
        }
    },
)

/**
 * 按当前启用目录解析一次调用。
 *
 * 目录就是 ChatToolFactory 这次真正下发的外部工具集合：模型自己编的名字、被禁用的工具、
 * 没挂到本会话的服务，全都在这里落地为 [ResolvedCall.Unknown]，绝不会凭名字扩权。
 */
private fun resolveCall(catalog: List<McpCatalogEntry>, input: JsonElement): ResolvedCall {
    val args = input as? JsonObject ?: return ResolvedCall.Unknown("")
    val requested = args.stringOrNull("name")?.trim().orEmpty()
    if (requested.isEmpty()) return ResolvedCall.Unknown(requested)
    val entry = resolveEntry(catalog, requested) ?: return ResolvedCall.Unknown(requested)
    return when (val raw = args["arguments"]) {
        null, is JsonNull -> ResolvedCall.Found(entry, JsonObject(emptyMap()))
        is JsonObject -> ResolvedCall.Found(entry, raw)
        else -> ResolvedCall.BadArguments(requested)
    }
}

private fun resolveEntry(catalog: List<McpCatalogEntry>, requested: String): McpCatalogEntry? {
    catalog.firstOrNull { it.name.equals(requested, ignoreCase = true) }?.let { return it }
    return catalog.filter { it.rawName.isNotEmpty() && it.rawName.equals(requested, ignoreCase = true) }
        .singleOrNull()
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
