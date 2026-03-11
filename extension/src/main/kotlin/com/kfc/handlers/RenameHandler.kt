package com.kfc.handlers

import com.kfc.KfcContext
import kotlinx.serialization.json.*

object RenameHandler {

    fun rename(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["sig"] ?: return """{"error":"Missing 'sig' parameter"}"""
        val newName = params["new_name"] ?: return """{"error":"Missing 'new_name' parameter"}"""

        for (dex in ctx.dexUnits) {
            // Try class
            val cls = dex.getClass(sig)
            if (cls != null) {
                val ok = cls.setName(newName)
                return buildJsonObject {
                    put("type", "class")
                    put("signature", sig)
                    put("new_name", newName)
                    put("success", ok)
                }.toString()
            }

            // Try method
            val method = dex.getMethod(sig)
            if (method != null) {
                val ok = method.setName(newName)
                return buildJsonObject {
                    put("type", "method")
                    put("signature", sig)
                    put("new_name", newName)
                    put("success", ok)
                }.toString()
            }

            // Try field
            val field = dex.getField(sig)
            if (field != null) {
                val ok = field.setName(newName)
                return buildJsonObject {
                    put("type", "field")
                    put("signature", sig)
                    put("new_name", newName)
                    put("success", ok)
                }.toString()
            }
        }

        return """{"error":"Item not found: $sig"}"""
    }
}
