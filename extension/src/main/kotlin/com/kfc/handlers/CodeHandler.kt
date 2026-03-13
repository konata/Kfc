package com.kfc.handlers

import com.kfc.KfcContext
import com.pnfsoftware.jeb.core.actions.ActionContext
import com.pnfsoftware.jeb.core.actions.ActionOverridesData
import com.pnfsoftware.jeb.core.actions.Actions
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexClass
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod
import kotlinx.serialization.json.*

object CodeHandler {

    fun listClasses(ctx: KfcContext, params: Map<String, String>): String {
        val filter = params["filter"] ?: ""
        val offset = params["offset"]?.toIntOrNull() ?: 0
        val limit = params["limit"]?.toIntOrNull() ?: 200

        val allClasses = mutableListOf<IDexClass>()
        for (dex in ctx.dexUnits) {
            dex.classes?.let { allClasses.addAll(it) }
        }

        val filtered = if (filter.isNotEmpty()) {
            allClasses.filter { it.classType.signature.contains(filter, ignoreCase = true) }
        } else {
            allClasses
        }

        val page = filtered.drop(offset).take(limit)

        return buildJsonObject {
            put("total", filtered.size)
            put("offset", offset)
            put("limit", limit)
            putJsonArray("classes") {
                page.forEach { cls ->
                    add(buildJsonObject {
                        put("signature", cls.classType.signature)
                        put("flags", cls.genericFlags)
                        put("supertype", cls.supertypeSignature ?: "")
                        putJsonArray("interfaces") {
                            cls.interfaceSignatures?.forEach { iface ->
                                add(iface)
                            }
                        }
                        put("method_count", cls.methods?.size ?: 0)
                        put("field_count", cls.fields?.size ?: 0)
                    })
                }
            }
        }.toString()
    }

    fun decompileClass(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["cls"] ?: return """{"error":"Missing 'cls' parameter"}"""

        for (dex in ctx.dexUnits) {
            val cls = dex.getClass(sig) ?: continue
            val decomp = ctx.getDecompiler(dex)
                ?: return """{"error":"Decompiler not available"}"""

            if (!decomp.decompileClass(sig)) {
                return """{"error":"Decompilation failed for $sig"}"""
            }

            val text = decomp.getDecompiledClassText(sig)
                ?: return """{"error":"No decompiled text for $sig"}"""

            return buildJsonObject {
                put("signature", sig)
                put("source", text)
            }.toString()
        }

        return """{"error":"Class not found: $sig"}"""
    }

    fun decompileMethod(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["sig"] ?: return """{"error":"Missing 'sig' parameter"}"""

        for (dex in ctx.dexUnits) {
            val method = dex.getMethod(sig) ?: continue
            val classSig = method.classType.signature
            val decomp = ctx.getDecompiler(dex)
                ?: return """{"error":"Decompiler not available"}"""

            if (!decomp.decompileClass(classSig)) {
                return """{"error":"Failed to decompile enclosing class $classSig"}"""
            }

            val methodText = decomp.getDecompiledMethodText(sig)
            if (methodText != null) {
                return buildJsonObject {
                    put("method_signature", sig)
                    put("class_signature", classSig)
                    put("source", methodText)
                }.toString()
            }

            val classText = decomp.getDecompiledClassText(classSig)
            return buildJsonObject {
                put("method_signature", sig)
                put("class_signature", classSig)
                put("source", classText ?: "(decompilation produced no output)")
                put("note", "Full class returned; method-level extraction not available")
            }.toString()
        }

        return """{"error":"Method not found: $sig"}"""
    }

    fun classHierarchy(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["cls"] ?: return """{"error":"Missing 'cls' parameter"}"""

        for (dex in ctx.dexUnits) {
            val cls = dex.getClass(sig) ?: continue

            val chain = mutableListOf<String>()
            var currentSig: String? = sig
            while (currentSig != null) {
                chain.add(currentSig)
                val c = dex.getClass(currentSig)
                currentSig = c?.supertypeSignature
            }

            val interfaces = mutableSetOf<String>()
            fun collectInterfaces(c: IDexClass?) {
                c?.interfaceSignatures?.forEach { iface ->
                    if (interfaces.add(iface)) {
                        collectInterfaces(dex.getClass(iface))
                    }
                }
            }
            collectInterfaces(cls)

            val subclasses = dex.classes
                ?.filter { it.supertypeSignature == sig }
                ?.map { it.classType.signature }
                ?: emptyList()

            return buildJsonObject {
                put("signature", sig)
                putJsonArray("superclass_chain") { chain.forEach { add(it) } }
                putJsonArray("interfaces") { interfaces.forEach { add(it) } }
                putJsonArray("subclasses") { subclasses.forEach { add(it) } }
            }.toString()
        }

        return """{"error":"Class not found: $sig"}"""
    }

    fun methodCfg(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["sig"] ?: return """{"error":"Missing 'sig' parameter"}"""

        for (dex in ctx.dexUnits) {
            val method = dex.getMethod(sig) ?: continue
            val data = method.data ?: return """{"error":"Method has no body (abstract/native?)"}"""
            val codeItem = data.codeItem
                ?: return """{"error":"No code item available"}"""

            val insns = codeItem.instructions
                ?: return """{"error":"No instructions available"}"""

            return buildJsonObject {
                put("method_signature", sig)
                put("instruction_count", insns.size)
                put("register_count", codeItem.registerCount)
                putJsonArray("instructions") {
                    insns.take(500).forEach { insn ->
                        add(buildJsonObject {
                            put("offset", insn.offset)
                            put("opcode", insn.mnemonic ?: "unknown")
                            put("text", insn.format(null) ?: "")
                        })
                    }
                }
            }.toString()
        }

        return """{"error":"Method not found: $sig"}"""
    }

    fun getOverrides(ctx: KfcContext, params: Map<String, String>): String {
        val sig = params["sig"] ?: return """{"error":"Missing 'sig' parameter"}"""

        for (dex in ctx.dexUnits) {
            val method = dex.getMethod(sig) ?: continue
            val address = method.getSignature(false)

            val actionCtx = ActionContext(Actions.QUERY_OVERRIDES, address)
            val data = ActionOverridesData()

            if (!dex.prepareExecution(actionCtx, data)) {
                return """{"error":"QUERY_OVERRIDES not supported or failed for $sig"}"""
            }
            dex.executeAction(actionCtx, data)

            val children = data.children?.map { it.getSignature(false) } ?: emptyList()
            val parents = data.parents?.map { it.getSignature(false) } ?: emptyList()

            return buildJsonObject {
                put("method_signature", sig)
                putJsonArray("children") { children.forEach { add(it) } }
                putJsonArray("parents") { parents.forEach { add(it) } }
                put("children_count", children.size)
                put("parents_count", parents.size)
            }.toString()
        }

        return """{"error":"Method not found: $sig"}"""
    }

    private val IDexClass.supertypeSignature: String?
        get() = getSupertypeSignature(false)

    private val IDexClass.interfaceSignatures: Array<String>?
        get() = getInterfaceSignatures(false)
}
