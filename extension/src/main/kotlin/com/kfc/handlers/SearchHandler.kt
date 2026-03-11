package com.kfc.handlers

import com.kfc.KfcContext
import com.pnfsoftware.jeb.core.units.code.android.dex.DexPoolType
import kotlinx.serialization.json.*

object SearchHandler {

    fun searchStrings(ctx: KfcContext, params: Map<String, String>): String {
        val pattern = params["pattern"] ?: return """{"error":"Missing 'pattern' parameter"}"""
        val limit = params["limit"]?.toIntOrNull() ?: 200

        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return """{"error":"Invalid regex: ${e.message}"}"""
        }

        val results = mutableListOf<JsonObject>()

        for (dex in ctx.dexUnits) {
            val strings = dex.strings ?: continue
            for (str in strings) {
                if (results.size >= limit) break
                val value = str.value ?: continue
                if (regex.containsMatchIn(value)) {
                    val refMgr = dex.getReferenceManager()

                    val refs = refMgr?.getReferences(DexPoolType.STRING, str.index)

                    results.add(buildJsonObject {
                        put("value", value)
                        put("index", str.index)
                        putJsonArray("referenced_by") {
                            refs?.forEach { addr ->
                                add(buildJsonObject {
                                    put("address", addr.internalAddress ?: addr.toString())
                                })
                            }
                        }
                    })
                }
            }
        }

        return buildJsonObject {
            put("pattern", pattern)
            put("count", results.size)
            put("limit", limit)
            putJsonArray("matches") { results.forEach { add(it) } }
        }.toString()
    }

    // IDexString inherits getIndex() from ICodeItem and getValue() from ICodeString
}
