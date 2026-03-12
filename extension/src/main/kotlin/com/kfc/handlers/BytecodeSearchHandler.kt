package com.kfc.handlers

import com.kfc.KfcContext
import kotlinx.serialization.json.*

object BytecodeSearchHandler {

    fun search(ctx: KfcContext, params: Map<String, String>): String {
        val pattern = params["pattern"] ?: return """{"error":"Missing 'pattern' parameter"}"""
        val limit = params["limit"]?.toIntOrNull() ?: 100

        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return """{"error":"Invalid regex pattern: ${e.message}"}"""
        }

        val results = mutableListOf<JsonObject>()

        for (dex in ctx.dexUnits) {
            val methods = dex.methods ?: continue
            for (method in methods) {
                if (results.size >= limit) break
                val data = method.data ?: continue
                val codeItem = data.codeItem ?: continue
                val instructions = codeItem.instructions ?: continue

                for (insn in instructions) {
                    if (results.size >= limit) break
                    val text = try {
                        insn.format(dex) ?: continue
                    } catch (_: Exception) {
                        continue
                    }

                    if (regex.containsMatchIn(text)) {
                        results.add(buildJsonObject {
                            put("method", method.getSignature(false))
                            put("class", method.classType.getSignature(false))
                            put("offset", insn.offset)
                            put("instruction", text)
                        })
                    }
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
}
