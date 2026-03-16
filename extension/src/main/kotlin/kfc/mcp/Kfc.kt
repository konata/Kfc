package kfc.mcp

import com.pnfsoftware.jeb.client.api.IClientContext
import com.pnfsoftware.jeb.client.api.IScript
import com.pnfsoftware.jeb.core.IEnginesContext
import com.pnfsoftware.jeb.core.IRuntimeProject
import com.pnfsoftware.jeb.core.units.IUnit
import com.pnfsoftware.jeb.core.units.IXmlUnit
import com.pnfsoftware.jeb.core.units.code.android.IApkUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URLDecoder
import kfc.mcp.Handler.*

class Extension : IScript {
    override fun run(ctx: IClientContext) {
        val engine = ctx.enginesContext ?: return println("[kfc] ERROR: No engines context available.")
        val port = System.getProperty("kfc.port", "9527").toIntOrNull() ?: 9527
        println("[kfc] Starting bridge on port $port ...")
        val stop = Kfc.start(engine, port)
        println("[kfc] Ready at http://localhost:$port")
        println("[kfc] Waiting for load_apk ...")
        runCatching { Thread.currentThread().join() }.onFailure {
            stop(1)
            println("[kfc] Stopped.")
        }
    }
}

data class Ctx(
    val engine: IEnginesContext,
    var project: IRuntimeProject? = null,
    var currentPath: String? = null,
    var units: List<IUnit> = emptyList(),
    var apk: IApkUnit? = null,
    var dexes: List<IDexUnit> = emptyList(),
    var primaryDex: IDexUnit? = null,
    var manifest: IXmlUnit? = null,
)

object Kfc {
    fun start(engine: IEnginesContext, port: Int): (Int) -> Unit {
        val ctx = Ctx(engine)
        return HttpServer.create(InetSocketAddress(port), 0).run {
            val route = route(ctx)
            route("/api/meta/project", Project)
            route("/api/meta/manifest", Manifest)
            route("/api/meta/units", Units)
            route("/api/meta/permissions", Permissions)
            route("/api/meta/components", Components)

            route("/api/classes", Classes)
            route("/api/decompile/class", DecompileClass)
            route("/api/decompile/method", DecompileMethod)
            route("/api/hierarchy", Hierarchy)
            route("/api/overrides", Overrides)
            route("/api/xrefs", References)
            route("/api/strings", SearchStrings)
            route("/api/method/cfg", ControlFlow)
            route("/api/bytecode/search", SearchBytecode)

            route("/api/load", Load)
            route("/api/rename", Rename)
            get("/api/health") { """{"status":"ok"}""" }

            executor = null
            start()
            this::stop
        }
    }
}

val HttpExchange.queries: Map<String, String>
    get() = requestURI.query?.split("&")?.filter(String::isNotBlank)?.associate {
        val pair = it.split("=", limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    } ?: emptyMap()

fun HttpServer.get(path: String, body: (HttpExchange) -> String) {
    createContext(path) { ex -> runCatching { body(ex) }.getOrElse { err(it.message ?: "unknown") }.send(ex) }
}

fun HttpServer.route(ctx: Ctx) = { path: String, handler: Handler ->
    get(path) { handler.run(ctx, it.queries) }
}

fun String.send(ex: HttpExchange) {
    val bytes = toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

fun err(msg: String) = buildJsonObject { put("error", msg) }.toString()
