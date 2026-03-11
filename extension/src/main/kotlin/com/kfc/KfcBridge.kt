package com.kfc

import com.kfc.handlers.*
import com.pnfsoftware.jeb.core.IEnginesContext
import com.pnfsoftware.jeb.core.IRuntimeProject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class KfcBridge(
    private val enginesContext: IEnginesContext,
    private val project: IRuntimeProject,
    private val port: Int,
) {
    private lateinit var server: HttpServer

    fun start() {
        val ctx = KfcContext(enginesContext, project)

        server = HttpServer.create(InetSocketAddress(port), 0)

        // Meta
        server.createContext("/api/meta/project") { handle(it) { MetaHandler.projectInfo(ctx) } }
        server.createContext("/api/meta/manifest") { handle(it) { MetaHandler.manifest(ctx) } }
        server.createContext("/api/meta/units") { handle(it) { MetaHandler.listUnits(ctx) } }
        server.createContext("/api/meta/permissions") { handle(it) { MetaHandler.permissions(ctx) } }
        server.createContext("/api/meta/components") { handle(it) { MetaHandler.components(ctx) } }

        // Code navigation
        server.createContext("/api/classes") { handle(it) { CodeHandler.listClasses(ctx, it.queryParams) } }
        server.createContext("/api/decompile/class") { handle(it) { CodeHandler.decompileClass(ctx, it.queryParams) } }
        server.createContext("/api/decompile/method") { handle(it) { CodeHandler.decompileMethod(ctx, it.queryParams) } }
        server.createContext("/api/hierarchy") { handle(it) { CodeHandler.classHierarchy(ctx, it.queryParams) } }
        server.createContext("/api/xrefs") { handle(it) { XrefHandler.getXrefs(ctx, it.queryParams) } }
        server.createContext("/api/strings") { handle(it) { SearchHandler.searchStrings(ctx, it.queryParams) } }
        server.createContext("/api/method/cfg") { handle(it) { CodeHandler.methodCfg(ctx, it.queryParams) } }
        server.createContext("/api/bytecode/search") { handle(it) { BytecodeSearchHandler.search(ctx, it.queryParams) } }

        // Rename
        server.createContext("/api/rename") { handle(it) { RenameHandler.rename(ctx, it.queryParams) } }

        // Health
        server.createContext("/api/health") { handle(it) { """{"status":"ok"}""" } }

        server.executor = null
        server.start()
    }

    fun stop() {
        if (::server.isInitialized) server.stop(1)
    }

    private fun handle(exchange: HttpExchange, block: () -> String) {
        try {
            val result = block()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            val bytes = result.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            val error = """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}"""
            val bytes = error.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

val HttpExchange.queryParams: Map<String, String>
    get() {
        val query = requestURI.query ?: return emptyMap()
        return query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }
