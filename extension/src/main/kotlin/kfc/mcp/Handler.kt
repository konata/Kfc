package kfc.mcp

import com.pnfsoftware.jeb.core.Artifact
import com.pnfsoftware.jeb.core.actions.ActionContext
import com.pnfsoftware.jeb.core.actions.ActionOverridesData
import com.pnfsoftware.jeb.core.actions.Actions
import com.pnfsoftware.jeb.core.input.FileInput
import com.pnfsoftware.jeb.core.units.IUnit
import com.pnfsoftware.jeb.core.units.IXmlUnit
import com.pnfsoftware.jeb.core.units.code.ICodeItem
import com.pnfsoftware.jeb.core.units.code.android.IApkUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit
import com.pnfsoftware.jeb.core.units.code.android.dex.DexPoolType
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexAddress
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexClass
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

sealed class Handler(val run: Ctx.(Map<String, String>) -> String) {
    companion object {
        fun recover(block: Ctx.(Map<String, String>) -> String): Ctx.(Map<String, String>) -> String =
            { queries -> runCatching { block(queries) }.getOrElse { err(it.message ?: "unknown") } }
    }

    data object Load : Handler(recover { queries ->
        val path = queries["path"].orEmpty()
        val file = File(path).takeIf { it.isFile } ?: fail("File not found: $path")

        val previousPath = currentPath
        currentPath = file.absolutePath
        project = engine.loadProject("kfc-${file.nameWithoutExtension}")?.apply { processArtifact(Artifact(file.name, FileInput(file))) }
        units = project?.liveArtifacts.orEmpty().flatMap { it.units.orEmpty() }.flatMap { it.all }
        apk = units.filterIsInstance<IApkUnit>().firstOrNull()
        dexes = units.filterIsInstance<IDexUnit>()
        primaryDex = dexes.firstOrNull()
        manifest = units.filterIsInstance<IXmlUnit>().firstOrNull { it.name.equals("AndroidManifest.xml", true) || it.name.equals("Manifest", true) }

        println("[kfc] Loaded: ${file.absolutePath} (units=${units.size}, dex=${dexes.size})")
        buildJsonObject {
            put("success", true)
            put("path", file.absolutePath)
            put("units", units.size)
            put("dex_count", dexes.size)
            previousPath?.let { put("previous", it) }
        }.toString()
    })

    data object Project : Handler(recover { _ ->
        project?.let { project ->
            buildJsonObject {
                put("loaded", true)
                currentPath?.let { put("path", it) }
                put("project_name", project.name)
                put("apk_detected", apk != null)
                put("dex_count", dexes.size)
                put("total_units", units.size)
                putJsonArray("artifacts") { project.liveArtifacts.orEmpty().forEach { add(buildJsonObject { put("name", it.artifact?.name ?: "unknown") }) } }
            }.toString()
        } ?: buildJsonObject {
            put("loaded", false)
            put("message", "No project loaded. Use load_apk first.")
        }.toString()
    })

    data object Manifest : Handler(recover { _ ->
        manifest?.let { buildJsonObject { put("content", xmlText(it)) }.toString() } ?: err("AndroidManifest.xml not found")
    })

    data object Units : Handler(recover { _ ->
        buildJsonArray {
            units.forEach { unit ->
                add(buildJsonObject {
                    put("name", unit.name)
                    put("type", unit.javaClass.simpleName)
                    put("description", unit.description ?: "")
                    put("status", unit.status ?: "")
                })
            }
        }.toString()
    })

    data object Permissions : Handler(recover { _ ->
        val text = manifest?.let(::xmlText) ?: """{"permissions":[]}"""
        val list = Regex("""android:name="(android\.permission\.[^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        buildJsonObject { putJsonArray("permissions") { list.forEach { add(JsonPrimitive(it)) } } }.toString()
    })

    data object Components : Handler(recover { _ ->
        val text = manifest?.let(::xmlText) ?: """{"activities":[],"services":[],"receivers":[],"providers":[]}"""
        fun find(tag: String) = Regex("""<$tag[^>]*android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        buildJsonObject {
            putJsonArray("activities") { find("activity").forEach { add(JsonPrimitive(it)) } }
            putJsonArray("services") { find("service").forEach { add(JsonPrimitive(it)) } }
            putJsonArray("receivers") { find("receiver").forEach { add(JsonPrimitive(it)) } }
            putJsonArray("providers") { find("provider").forEach { add(JsonPrimitive(it)) } }
        }.toString()
    })

    data object Classes : Handler(recover { queries ->
        val filter = queries["filter"] ?: ""
        val offset = queries["offset"]?.toIntOrNull() ?: 0
        val limit = queries["limit"]?.toIntOrNull() ?: 200
        val classes = dexes.flatMap { it.classes.orEmpty() }
        val list = classes.filter { it.classType.signature.contains(filter, true) }
        buildJsonObject {
            put("total", list.size)
            put("offset", offset)
            put("limit", limit)
            putJsonArray("classes") {
                list.drop(offset).take(limit).forEach { cls ->
                    add(buildJsonObject {
                        put("signature", cls.classType.signature)
                        put("flags", cls.genericFlags)
                        put("supertype", cls.sup ?: "")
                        putJsonArray("interfaces") { cls.ifs?.forEach { add(JsonPrimitive(it)) } }
                        put("method_count", cls.methods?.size ?: 0)
                        put("field_count", cls.fields?.size ?: 0)
                    })
                }
            }
        }.toString()
    })

    data object DecompileClass : Handler(recover { queries ->
        val signature = queries.required("cls")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(signature)?.let {
                val decompiler = decompiler(this, dex).required("Decompiler not available")
                if (!decompiler.decompileClass(signature)) fail("Decompilation failed for $signature")
                buildJsonObject { put("signature", signature); put("source", decompiler.getDecompiledClassText(signature).required("No decompiled text for $signature")) }.toString()
            }
        } ?: err("Class not found: $signature")
    })

    data object DecompileMethod : Handler(recover { queries ->
        val signature = queries.required("sig")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getMethod(signature)?.let { method ->
                val classSignature = method.classType.signature
                val decompiler = decompiler(this, dex).required("Decompiler not available")
                if (!decompiler.decompileClass(classSignature)) fail("Failed to decompile enclosing class $classSignature")
                val source = decompiler.getDecompiledMethodText(signature) ?: decompiler.getDecompiledClassText(classSignature) ?: "(decompilation produced no output)"
                buildJsonObject {
                    put("method_signature", signature)
                    put("class_signature", classSignature)
                    put("source", source)
                    if (decompiler.getDecompiledMethodText(signature) == null) put("note", "Full class returned; method-level extraction not available")
                }.toString()
            }
        } ?: err("Method not found: $signature")
    })

    data object Hierarchy : Handler(recover { queries ->
        val signature = queries.required("cls")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(signature)?.let { cls ->
                val chain = generateSequence(signature) { dex.getClass(it)?.sup }.toList()
                val subclasses = dex.classes.orEmpty().asSequence().filter { it.sup == signature }.map { it.classType.signature }.toList()
                buildJsonObject {
                    put("signature", signature)
                    putJsonArray("superclass_chain") { chain.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("interfaces") { cls.allInterfaces(dex).forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("subclasses") { subclasses.forEach { add(JsonPrimitive(it)) } }
                }.toString()
            }
        } ?: err("Class not found: $signature")
    })

    data object ClassMethods : Handler(recover { queries ->
        val signature = queries.required("cls")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(signature)?.let {
                val chain = generateSequence(signature) { dex.getClass(it)?.sup }.toList()
                val classesBySignature = chain.associateWith { sig -> dex.getClass(sig) }
                buildJsonObject {
                    put("query_class", signature)
                    put("resolved", true)
                    put("class_count", chain.size)
                    putJsonArray("class_chain") {
                        chain.forEachIndexed { index, classSignature ->
                            val cls = classesBySignature[classSignature]
                            add(buildJsonObject {
                                put("class", classSignature)
                                put("resolved", cls != null)
                                put("super", cls?.sup ?: "")
                                putJsonArray("interfaces") { cls?.ifs?.forEach { add(JsonPrimitive(it)) } }
                                putJsonArray("methods") {
                                    cls?.methods.orEmpty().forEach { method ->
                                        add(describe(method, chain.drop(index + 1).mapNotNull(classesBySignature::get)))
                                    }
                                }
                            })
                        }
                    }
                }.toString()
            }
        } ?: err("Class not found: $signature")
    })

    data object Overrides : Handler(recover { queries ->
        val signature = queries.required("sig")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getMethod(signature)?.let { method ->
                val action = ActionContext(dex, Actions.QUERY_OVERRIDES, method.itemId, method.getSignature(false))
                val data = ActionOverridesData()
                if (!dex.prepareExecution(action, data)) fail("QUERY_OVERRIDES not supported or failed for $signature")
                dex.executeAction(action, data)
                val children = data.children?.map { it.getSignature(false) }.orEmpty()
                val parents = data.parents?.map { it.getSignature(false) }.orEmpty()
                buildJsonObject {
                    put("method_signature", signature)
                    putJsonArray("children") { children.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("parents") { parents.forEach { add(JsonPrimitive(it)) } }
                    put("children_count", children.size)
                    put("parents_count", parents.size)
                }.toString()
            }
        } ?: err("Method not found: $signature")
    })

    data object References : Handler(recover { queries ->
        val signature = queries.required("sig")
        val limit = queries["limit"]?.toIntOrNull() ?: 100
        dexes.firstNotNullOfOrNull { dex ->
            dex.referenceManager?.let { referenceManager ->
                dex.getMethod(signature)?.let { referenceList(signature, "method", referenceManager.getReferences(DexPoolType.METHOD, it.index), limit) }
                    ?: dex.getField(signature)?.let { referenceList(signature, "field", referenceManager.getReferences(DexPoolType.FIELD, it.index), limit) }
                    ?: dex.getClass(signature)?.let {
                        dex.getType(signature)?.let { type ->
                            referenceList(signature, "class", referenceManager.getReferences(DexPoolType.TYPE, type.index), limit)
                        }
                    }
            }
        } ?: err("Symbol not found: $signature")
    })

    data object SearchStrings : Handler(recover { queries ->
        val pattern = queries.required("pattern")
        val limit = queries["limit"]?.toIntOrNull() ?: 200
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { fail("Invalid regex pattern: ${it.message}") }
        val matches = dexes.asSequence().flatMap { dex ->
            dex.strings.orEmpty().asSequence().mapNotNull { string ->
                string.value?.takeIf { regex.containsMatchIn(it) }?.let { value ->
                    buildJsonObject {
                        put("value", value)
                        put("index", string.index)
                        putJsonArray("referenced_by") { dex.referenceManager?.getReferences(DexPoolType.STRING, string.index)?.forEach { add(buildJsonObject { put("address", it.addr) }) } }
                    }
                }
            }
        }.take(limit).toList()
        buildJsonObject {
            put("pattern", pattern)
            put("count", matches.size)
            put("limit", limit)
            putJsonArray("matches") { matches.forEach { add(it) } }
        }.toString()
    })

    data object ControlFlow : Handler(recover { queries ->
        val signature = queries.required("sig")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getMethod(signature)?.let { method ->
                val data = method.data.required("Method has no body (abstract/native?)")
                val code = data.codeItem.required("No code item available")
                val instructions = code.instructions.required("No instructions available")
                buildJsonObject {
                    put("method_signature", signature)
                    put("instruction_count", instructions.size)
                    put("register_count", code.registerCount)
                    putJsonArray("instructions") {
                        instructions.take(500).forEach { instruction ->
                            add(buildJsonObject {
                                put("offset", instruction.offset)
                                put("opcode", instruction.mnemonic ?: "unknown")
                                put("text", instruction.format(null) ?: "")
                            })
                        }
                    }
                }.toString()
            }
        } ?: err("Method not found: $signature")
    })

    data object SearchBytecode : Handler(recover { queries ->
        val pattern = queries.required("pattern")
        val limit = queries["limit"]?.toIntOrNull() ?: 100
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { fail("Invalid regex pattern: ${it.message}") }
        val matches = dexes.asSequence().flatMap { dex ->
            dex.methods.orEmpty().asSequence().flatMap { method ->
                method.data?.codeItem?.instructions.orEmpty().asSequence().mapNotNull { instruction ->
                    runCatching { instruction.format(dex) }.getOrNull()?.takeIf { regex.containsMatchIn(it) }?.let { text ->
                        buildJsonObject {
                            put("method", method.getSignature(false))
                            put("class", method.classType.getSignature(false))
                            put("offset", instruction.offset)
                            put("instruction", text)
                        }
                    }
                }
            }
        }.take(limit).toList()
        buildJsonObject {
            put("pattern", pattern)
            put("count", matches.size)
            put("limit", limit)
            putJsonArray("matches") { matches.forEach { add(it) } }
        }.toString()
    })

    data object Rename : Handler(recover { queries ->
        val signature = queries.required("sig")
        val name = queries.required("new_name")
        dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(signature)?.let {
                buildJsonObject {
                    put("type", "class")
                    put("signature", signature)
                    put("new_name", name)
                    put("success", it.setName(name))
                }.toString()
            } ?: dex.getMethod(signature)?.let {
                buildJsonObject {
                    put("type", "method")
                    put("signature", signature)
                    put("new_name", name)
                    put("success", it.setName(name))
                }.toString()
            } ?: dex.getField(signature)?.let {
                buildJsonObject {
                    put("type", "field")
                    put("signature", signature)
                    put("new_name", name)
                    put("success", it.setName(name))
                }.toString()
            }
        } ?: err("Item not found: $signature")
    })
}

val IUnit.all: List<IUnit>
    get() = listOf(this) + children.orEmpty().flatMap { it.all }

val IDexClass.sup: String?
    get() = getSupertypeSignature(false)

val IDexClass.ifs: Array<String>?
    get() = getInterfaceSignatures(false)

val IDexAddress.addr: String
    get() = internalAddress ?: toString()

class HandlerError(message: String) : RuntimeException(message)

fun fail(message: String): Nothing = throw HandlerError(message)

fun Map<String, String>.required(key: String) = get(key) ?: fail("Missing '$key' parameter")

fun <T> T?.required(message: String): T = this ?: fail(message)

fun IDexClass.allInterfaces(dex: IDexUnit): List<String> =
    ifs.orEmpty().flatMap { listOf(it) + dex.getClass(it)?.allInterfaces(dex).orEmpty() }.distinct()

fun xmlText(xml: IXmlUnit) = runCatching {
    StringWriter().also { out -> TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "yes"); transform(DOMSource(xml.document), StreamResult(out)) } }.toString()
}.getOrElse { "(failed to serialize XML: ${it.message})" }

fun decompiler(ctx: Ctx, dex: IDexUnit) =
    ctx.units.filterIsInstance<IDexDecompilerUnit>().firstOrNull() ?: dex.children.orEmpty().filterIsInstance<IDexDecompilerUnit>().firstOrNull()

fun referenceList(signature: String, kind: String, list: Collection<IDexAddress>?, limit: Int) = buildJsonObject {
    put("target", signature)
    put("type", kind)
    putJsonArray("references_to") { list.orEmpty().take(limit).forEach { add(buildJsonObject { put("address", it.addr) }) } }
    put("reference_count", list?.size ?: 0)
}.toString()

fun describe(method: IDexMethod, ancestors: List<IDexClass>) = buildJsonObject {
    val signature = method.getSignature(false)
    val data = method.data
    val flags = data?.accessFlags ?: method.genericFlags
    put("signature", signature)
    put("declared_in", method.classType.signature)
    put("name", method.getName(false))
    put("sub_signature", method.subSignature)
    put("generic_flags", method.genericFlags)
    put("access_flags", flags)
    put("public", data?.isPublic ?: flags.hasFlag(ICodeItem.FLAG_PUBLIC))
    put("protected", data?.isProtected ?: flags.hasFlag(ICodeItem.FLAG_PROTECTED))
    put("private", data?.isPrivate ?: flags.hasFlag(ICodeItem.FLAG_PRIVATE))
    put("static", data?.isStatic ?: flags.hasFlag(ICodeItem.FLAG_STATIC))
    put("final", data?.isFinal ?: flags.hasFlag(ICodeItem.FLAG_FINAL))
    put("abstract", data?.isAbstract ?: flags.hasFlag(ICodeItem.FLAG_ABSTRACT))
    put("native", data?.isNative ?: flags.hasFlag(ICodeItem.FLAG_NATIVE))
    put("synthetic", data?.isSynthetic ?: flags.hasFlag(ICodeItem.FLAG_SYNTHETIC))
    put("constructor", data?.isConstructor ?: flags.hasFlag(ICodeItem.FLAG_CONSTRUCTOR))
    put("has_code", data?.codeItem != null)
    val overridden = ancestors.firstNotNullOfOrNull { ancestor ->
        ancestor.methods.orEmpty().firstOrNull { it.subSignature == method.subSignature }?.getSignature(false)
    }
    if (overridden != null) put("overrides", overridden)
}

val IDexMethod.subSignature: String
    get() = getSignature(false).substringAfter("->")

fun Int.hasFlag(flag: Int) = this and flag != 0
