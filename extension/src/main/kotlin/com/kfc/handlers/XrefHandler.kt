package com.kfc.handlers

import com.kfc.KfcContext
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit
import com.pnfsoftware.jeb.core.units.code.android.dex.DexPoolType
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAddress
import kotlinx.serialization.json.*

object XrefHandler {

    fun getXrefs(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["sig"] ?: return """{"error":"Missing 'sig' parameter"}"""
        val limit = params["limit"]?.toIntOrNull() ?: 100

        for (dex in ctx.dexUnits) {
            val refMgr = dex.referenceManager ?: continue

            // Try as method
            val method = dex.getMethod(sig)
            if (method != null) {
                val refs = refMgr.getReferences(DexPoolType.METHOD, method.index)
                return buildXrefs(sig, "method", refs, dex, limit)
            }

            // Try as field
            val field = dex.getField(sig)
            if (field != null) {
                val refs = refMgr.getReferences(DexPoolType.FIELD, field.index)
                return buildXrefs(sig, "field", refs, dex, limit)
            }

            // Try as class/type
            val cls = dex.getClass(sig)
            if (cls != null) {
                val type = dex.getType(sig)
                if (type != null) {
                    val refs = refMgr.getReferences(DexPoolType.TYPE, type.index)
                    return buildXrefs(sig, "class", refs, dex, limit)
                }
            }
        }

        return """{"error":"Symbol not found: $sig"}"""
    }

    private fun buildXrefs(
        sig: String,
        type: String,
        refs: Collection<IDexAddress>?,
        dex: IDexUnit,
        limit: Int,
    ): String {
        return buildJsonObject {
            put("target", sig)
            put("type", type)
            putJsonArray("references_to") {
                var count = 0
                refs?.forEach { addr ->
                    if (count >= limit) return@forEach
                    add(buildJsonObject {
                        put("address", addr.address)
                    })
                    count++
                }
            }
            put("reference_count", refs?.size ?: 0)
        }.toString()
    }

    private val IDexUnit.referenceManager
        get() = this.getReferenceManager()

    private val IDexAddress.address: String
        get() = this.internalAddress ?: toString()
}
