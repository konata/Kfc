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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.io.File
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.NodeList

sealed class Handler<In, Out>(
    private val read: (Map<String, String>) -> In,
    private val write: (Out) -> String,
) {
    protected abstract fun Ctx.execute(input: In): Out

    fun handle(ctx: Ctx, queries: Map<String, String>) =
        runCatching { write(with(this@Handler) { ctx.execute(read(queries)) }) }
            .getOrElse { err(it.message ?: "unknown") }

    class Adhoc(
        private val body: Ctx.(Map<String, String>) -> Map<String, JsonElement>,
    ) : Handler<Map<String, String>, Map<String, JsonElement>>({ it }, ::render) {
        override fun Ctx.execute(input: Map<String, String>) = body(input)
    }

    class Raw(
        private val body: Ctx.(Map<String, String>) -> String,
    ) : Handler<Map<String, String>, String>({ it }, { it }) {
        override fun Ctx.execute(input: Map<String, String>) = body(input)
    }

    companion object {
        val json = Json { encodeDefaults = true }

        fun adhoc(body: Ctx.(Map<String, String>) -> Map<String, JsonElement>) = Adhoc(body)

        fun raw(body: Ctx.(Map<String, String>) -> String) = Raw(body)

        fun <T> encode(serializer: KSerializer<T>) = { value: T -> json.encodeToString(serializer, value) }
    }

    data object Load : Handler<String, Load.Out>({ it.required("path") }, encode(Out.serializer())) {
        override fun Ctx.execute(input: String): Out {
            val file = File(input).takeIf { it.isFile } ?: fail("File not found: $input")
            val previousPath = currentPath
            val loadedProject = engine.loadProject("kfc-${file.nameWithoutExtension}").required("Failed to load project: ${file.absolutePath}")

            loadedProject.processArtifact(Artifact(file.name, FileInput(file)))
            project = loadedProject
            currentPath = file.absolutePath
            units = loadedProject.liveArtifacts.orEmpty().flatMap { it.units.orEmpty() }.flatMap { it.all }
            apk = units.filterIsInstance<IApkUnit>().firstOrNull()
            dexes = units.filterIsInstance<IDexUnit>()
            primaryDex = dexes.firstOrNull()
            manifest = units.filterIsInstance<IXmlUnit>().firstOrNull { it.name.equals("AndroidManifest.xml", true) || it.name.equals("Manifest", true) }

            println("[kfc] Loaded: ${file.absolutePath} (units=${units.size}, dex=${dexes.size})")
            return Out(path = file.absolutePath, units = units.size, dexCount = dexes.size, previous = previousPath)
        }

        @Serializable data class Out(val success: Boolean = true, val path: String, val units: Int, @SerialName("dex_count") val dexCount: Int, val previous: String? = null)
    }

    data object Project : Handler<Unit, Project.Out>({ Unit }, encode(Out.serializer())) {
        override fun Ctx.execute(input: Unit) = project?.let { loadedProject ->
            Out(
                loaded = true,
                path = currentPath,
                projectName = loadedProject.name,
                apkDetected = apk != null,
                dexCount = dexes.size,
                totalUnits = units.size,
                artifacts = loadedProject.liveArtifacts.orEmpty().map { Artifact(it.artifact?.name ?: "unknown") },
            )
        } ?: Out(loaded = false, message = "No project loaded. Use load_apk first.")

        @Serializable data class Artifact(val name: String)
        @Serializable data class Out(val loaded: Boolean, val path: String? = null, @SerialName("project_name") val projectName: String? = null, @SerialName("apk_detected") val apkDetected: Boolean = false, @SerialName("dex_count") val dexCount: Int = 0, @SerialName("total_units") val totalUnits: Int = 0, val artifacts: List<Artifact> = emptyList(), val message: String? = null)
    }

    data object Manifest : Handler<Unit, Manifest.Out>({ Unit }, encode(Out.serializer())) {
        override fun Ctx.execute(input: Unit) = Out(manifest.required("AndroidManifest.xml not found").let(::xmlText))
        @Serializable data class Out(val content: String)
    }

    data object Units : Handler<Unit, List<Units.Item>>({ Unit }, encode(ListSerializer(Item.serializer()))) {
        override fun Ctx.execute(input: Unit) = units.map { unit ->
            Item(
                name = unit.name,
                type = unit.javaClass.simpleName,
                description = unit.description ?: "",
                status = unit.status ?: "",
            )
        }

        @Serializable data class Item(val name: String, val type: String, val description: String, val status: String)
    }

    data object Permissions : Handler<Unit, Permissions.Out>({ Unit }, encode(Out.serializer())) {
        override fun Ctx.execute(input: Unit) = Out(manifest?.permissions().orEmpty())

        @Serializable data class Out(val permissions: List<String>)
    }

    data object Components : Handler<Unit, Components.Out>({ Unit }, encode(Out.serializer())) {
        override fun Ctx.execute(input: Unit) = Out(
            activities = manifest?.components("activity").orEmpty(),
            services = manifest?.components("service").orEmpty(),
            receivers = manifest?.components("receiver").orEmpty(),
            providers = manifest?.components("provider").orEmpty(),
        )

        @Serializable data class Out(val activities: List<String>, val services: List<String>, val receivers: List<String>, val providers: List<String>)
    }

    data object Classes : Handler<Classes.In, Classes.Out>(
        { In(filter = it["filter"].orEmpty(), offset = it.int("offset", 0), limit = it.int("limit", 200)) },
        encode(Out.serializer()),
    ) {
        override fun Ctx.execute(input: In): Out {
            val classes = dexes.flatMap { it.classes.orEmpty() }
            val matches = classes.filter { it.classType.signature.contains(input.filter, true) }
            return Out(
                total = matches.size,
                offset = input.offset,
                limit = input.limit,
                classes = matches.drop(input.offset).take(input.limit).map { cls ->
                    Item(
                        signature = cls.classType.signature,
                        flags = cls.genericFlags,
                        supertype = cls.sup ?: "",
                        interfaces = cls.ifs.orEmpty().toList(),
                        methodCount = cls.methods?.size ?: 0,
                        fieldCount = cls.fields?.size ?: 0,
                    )
                },
            )
        }

        @Serializable data class In(val filter: String = "", val offset: Int = 0, val limit: Int = 200)
        @Serializable data class Item(val signature: String, val flags: Int, val supertype: String, val interfaces: List<String>, @SerialName("method_count") val methodCount: Int, @SerialName("field_count") val fieldCount: Int)
        @Serializable data class Out(val total: Int, val offset: Int, val limit: Int, val classes: List<Item>)
    }

    data object DecompileClass : Handler<String, DecompileClass.Out>({ it.required("cls") }, encode(Out.serializer())) {
        override fun Ctx.execute(input: String) = dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(input)?.let {
                val unit = decompiler(this, dex).required("Decompiler not available")
                if (!unit.decompileClass(input)) fail("Decompilation failed for $input")
                Out(input, unit.getDecompiledClassText(input).required("No decompiled text for $input"))
            }
        } ?: fail("Class not found: $input")

        @Serializable data class Out(val signature: String, val source: String)
    }

    data object DecompileMethod : Handler<String, DecompileMethod.Out>({ it.required("sig") }, encode(Out.serializer())) {
        override fun Ctx.execute(input: String) = dexes.firstNotNullOfOrNull { dex ->
            dex.getMethod(input)?.let { method ->
                val classSignature = method.classType.signature
                val unit = decompiler(this, dex).required("Decompiler not available")
                if (!unit.decompileClass(classSignature)) fail("Failed to decompile enclosing class $classSignature")

                val methodSource = unit.getDecompiledMethodText(input)
                val source = methodSource ?: unit.getDecompiledClassText(classSignature) ?: "(decompilation produced no output)"
                Out(
                    methodSignature = input,
                    classSignature = classSignature,
                    source = source,
                    note = if (methodSource == null) "Full class returned; method-level extraction not available" else null,
                )
            }
        } ?: fail("Method not found: $input")

        @Serializable data class Out(@SerialName("method_signature") val methodSignature: String, @SerialName("class_signature") val classSignature: String, val source: String, val note: String? = null)
    }

    data object Hierarchy : Handler<String, Hierarchy.Out>({ it.required("cls") }, encode(Out.serializer())) {
        override fun Ctx.execute(input: String) = dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(input)?.let { cls ->
                Out(
                    signature = input,
                    superclassChain = generateSequence(input) { dex.getClass(it)?.sup }.toList(),
                    interfaces = cls.allInterfaces(dex),
                    subclasses = dex.classes.orEmpty().asSequence().filter { it.sup == input }.map { it.classType.signature }.toList(),
                )
            }
        } ?: fail("Class not found: $input")

        @Serializable data class Out(val signature: String, @SerialName("superclass_chain") val superclassChain: List<String>, val interfaces: List<String>, val subclasses: List<String>)
    }

    data object ClassMethods : Handler<String, ClassMethods.Out>({ it.required("cls") }, encode(Out.serializer())) {
        override fun Ctx.execute(input: String) = dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(input)?.let {
                val chain = generateSequence(input) { dex.getClass(it)?.sup }.toList()
                val classesBySignature = chain.associateWith { signature -> dex.getClass(signature) }
                Out(
                    queryClass = input,
                    resolved = true,
                    classCount = chain.size,
                    classChain = chain.mapIndexed { index, classSignature ->
                        val cls = classesBySignature[classSignature]
                        Chain(
                            classSignature = classSignature,
                            resolved = cls != null,
                            superSignature = cls?.sup ?: "",
                            interfaces = cls?.ifs.orEmpty().toList(),
                            methods = cls?.methods.orEmpty().map { method ->
                                describe(method, chain.drop(index + 1).mapNotNull(classesBySignature::get))
                            },
                        )
                    },
                )
            }
        } ?: fail("Class not found: $input")

        @Serializable data class Method(val signature: String, @SerialName("declared_in") val declaredIn: String, val name: String, @SerialName("sub_signature") val subSignature: String, @SerialName("generic_flags") val genericFlags: Int, @SerialName("access_flags") val accessFlags: Int, val public: Boolean, val protected: Boolean, val private: Boolean, val static: Boolean, val final: Boolean, val abstract: Boolean, val native: Boolean, val synthetic: Boolean, val constructor: Boolean, @SerialName("has_code") val hasCode: Boolean, val overrides: String? = null)
        @Serializable data class Chain(@SerialName("class") val classSignature: String, val resolved: Boolean, @SerialName("super") val superSignature: String, val interfaces: List<String>, val methods: List<Method>)
        @Serializable data class Out(@SerialName("query_class") val queryClass: String, val resolved: Boolean, @SerialName("class_count") val classCount: Int, @SerialName("class_chain") val classChain: List<Chain>)
    }

    data object Overrides : Handler<String, Overrides.Out>({ it.required("sig") }, encode(Out.serializer())) {
        override fun Ctx.execute(input: String) = dexes.firstNotNullOfOrNull { dex ->
            dex.getMethod(input)?.let { method ->
                val action = ActionContext(dex, Actions.QUERY_OVERRIDES, method.itemId, method.getSignature(false))
                val data = ActionOverridesData()
                if (!dex.prepareExecution(action, data)) fail("QUERY_OVERRIDES not supported or failed for $input")
                dex.executeAction(action, data)

                val children = data.children?.map { it.getSignature(false) }.orEmpty()
                val parents = data.parents?.map { it.getSignature(false) }.orEmpty()
                Out(
                    methodSignature = input,
                    children = children,
                    parents = parents,
                    childrenCount = children.size,
                    parentsCount = parents.size,
                )
            }
        } ?: fail("Method not found: $input")

        @Serializable data class Out(@SerialName("method_signature") val methodSignature: String, val children: List<String>, val parents: List<String>, @SerialName("children_count") val childrenCount: Int, @SerialName("parents_count") val parentsCount: Int)
    }

    data object References : Handler<References.In, References.Out>(
        { In(sig = it.required("sig"), limit = it.int("limit", 100)) },
        encode(Out.serializer()),
    ) {
        override fun Ctx.execute(input: In) = dexes.firstNotNullOfOrNull { dex ->
            dex.referenceManager?.let { referenceManager ->
                dex.getMethod(input.sig)?.let { referenceList(input.sig, "method", referenceManager.getReferences(DexPoolType.METHOD, it.index), input.limit) }
                    ?: dex.getField(input.sig)?.let { referenceList(input.sig, "field", referenceManager.getReferences(DexPoolType.FIELD, it.index), input.limit) }
                    ?: dex.getClass(input.sig)?.let {
                        dex.getType(input.sig)?.let { type ->
                            referenceList(input.sig, "class", referenceManager.getReferences(DexPoolType.TYPE, type.index), input.limit)
                        }
                    }
            }
        } ?: fail("Symbol not found: ${input.sig}")

        @Serializable data class In(val sig: String, val limit: Int = 100)
        @Serializable data class Ref(val address: String)
        @Serializable data class Out(val target: String, val type: String, @SerialName("references_to") val referencesTo: List<Ref>, @SerialName("reference_count") val referenceCount: Int)
    }

    data object SearchStrings : Handler<SearchStrings.In, SearchStrings.Out>(
        { In(pattern = it.required("pattern"), limit = it.int("limit", 200)) },
        encode(Out.serializer()),
    ) {
        override fun Ctx.execute(input: In): Out {
            val regex = runCatching { Regex(input.pattern, RegexOption.IGNORE_CASE) }.getOrElse { fail("Invalid regex pattern: ${it.message}") }
            val matches = dexes.asSequence().flatMap { dex ->
                dex.strings.orEmpty().asSequence().mapNotNull { string ->
                    string.value?.takeIf { regex.containsMatchIn(it) }?.let { value ->
                        Match(
                            value = value,
                            index = string.index,
                            referencedBy = dex.referenceManager?.getReferences(DexPoolType.STRING, string.index).orEmpty().map { Ref(it.addr) },
                        )
                    }
                }
            }.take(input.limit).toList()

            return Out(pattern = input.pattern, count = matches.size, limit = input.limit, matches = matches)
        }

        @Serializable data class In(val pattern: String, val limit: Int = 200)
        @Serializable data class Ref(val address: String)
        @Serializable data class Match(val value: String, val index: Int, @SerialName("referenced_by") val referencedBy: List<Ref>)
        @Serializable data class Out(val pattern: String, val count: Int, val limit: Int, val matches: List<Match>)
    }

    data object ControlFlow : Handler<ControlFlow.In, ControlFlow.Out>(
        { In(it.required("sig")) },
        encode(Out.serializer()),
    ) {
        override fun Ctx.execute(input: In) = dexes.firstNotNullOfOrNull { dex ->
            dex.getMethod(input.sig)?.let { method ->
                val data = method.data.required("Method has no body (abstract/native?)")
                val code = data.codeItem.required("No code item available")
                val instructions = code.instructions.required("No instructions available")
                Out(
                    methodSignature = input.sig,
                    instructionCount = instructions.size,
                    registerCount = code.registerCount,
                    instructions = instructions.take(500).map { instruction ->
                        Item(
                            offset = instruction.offset,
                            opcode = instruction.mnemonic ?: "unknown",
                            text = instruction.format(null) ?: "",
                        )
                    },
                )
            }
        } ?: fail("Method not found: ${input.sig}")

        @Serializable data class In(val sig: String)
        @Serializable data class Item(val offset: Long, val opcode: String, val text: String)
        @Serializable data class Out(@SerialName("method_signature") val methodSignature: String, @SerialName("instruction_count") val instructionCount: Int, @SerialName("register_count") val registerCount: Int, val instructions: List<Item>)
    }

    data object SearchBytecode : Handler<SearchBytecode.In, SearchBytecode.Out>(
        { In(pattern = it.required("pattern"), limit = it.int("limit", 100)) },
        encode(Out.serializer()),
    ) {
        override fun Ctx.execute(input: In): Out {
            val regex = runCatching { Regex(input.pattern, RegexOption.IGNORE_CASE) }.getOrElse { fail("Invalid regex pattern: ${it.message}") }
            val matches = dexes.asSequence().flatMap { dex ->
                dex.methods.orEmpty().asSequence().flatMap { method ->
                    method.data?.codeItem?.instructions.orEmpty().asSequence().mapNotNull { instruction ->
                        runCatching { instruction.format(dex) }.getOrNull()?.takeIf { regex.containsMatchIn(it) }?.let { text ->
                            Match(
                                method = method.getSignature(false),
                                classSignature = method.classType.getSignature(false),
                                offset = instruction.offset,
                                instruction = text,
                            )
                        }
                    }
                }
            }.take(input.limit).toList()

            return Out(pattern = input.pattern, count = matches.size, limit = input.limit, matches = matches)
        }

        @Serializable data class In(val pattern: String, val limit: Int = 100)
        @Serializable data class Match(val method: String, @SerialName("class") val classSignature: String, val offset: Long, val instruction: String)
        @Serializable data class Out(val pattern: String, val count: Int, val limit: Int, val matches: List<Match>)
    }

    data object Rename : Handler<Rename.In, Rename.Out>(
        { In(sig = it.required("sig"), newName = it.required("new_name")) },
        encode(Out.serializer()),
    ) {
        override fun Ctx.execute(input: In) = dexes.firstNotNullOfOrNull { dex ->
            dex.getClass(input.sig)?.let { Out("class", input.sig, input.newName, it.setName(input.newName)) }
                ?: dex.getMethod(input.sig)?.let { Out("method", input.sig, input.newName, it.setName(input.newName)) }
                ?: dex.getField(input.sig)?.let { Out("field", input.sig, input.newName, it.setName(input.newName)) }
        } ?: fail("Item not found: ${input.sig}")

        @Serializable data class In(val sig: String, val newName: String)
        @Serializable data class Out(val type: String, val signature: String, @SerialName("new_name") val newName: String, val success: Boolean)
    }
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

fun Map<String, String>.int(key: String, default: Int) = get(key)?.toIntOrNull() ?: default

fun <T> T?.required(message: String): T = this ?: fail(message)

fun IDexClass.allInterfaces(dex: IDexUnit): List<String> =
    ifs.orEmpty().flatMap { listOf(it) + dex.getClass(it)?.allInterfaces(dex).orEmpty() }.distinct()

fun xmlText(xml: IXmlUnit) = runCatching {
    StringWriter().also { out ->
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            transform(DOMSource(xml.document), StreamResult(out))
        }
    }.toString()
}.getOrElse { "(failed to serialize XML: ${it.message})" }

fun IXmlUnit.permissions() =
    document.documentElement?.children.orEmpty()
        .filter { it.tagName.startsWith("uses-permission") }
        .mapNotNull { it.android("name") }
        .distinct()

fun IXmlUnit.components(tag: String) =
    document.documentElement?.first("application")?.children.orEmpty()
        .filter { it.tagName == tag }
        .mapNotNull { it.android("name") }

fun Element.android(name: String) =
    getAttributeNS("http://schemas.android.com/apk/res/android", name).takeIf(String::isNotBlank)
        ?: getAttribute("android:$name").takeIf(String::isNotBlank)

val Element.children: List<Element>
    get() = childNodes.elements()

fun Element.first(tag: String) = children.firstOrNull { it.tagName == tag }

fun NodeList.elements() = (0 until length).mapNotNull { item(it) as? Element }

fun decompiler(ctx: Ctx, dex: IDexUnit) =
    ctx.units.filterIsInstance<IDexDecompilerUnit>().firstOrNull() ?: dex.children.orEmpty().filterIsInstance<IDexDecompilerUnit>().firstOrNull()

fun referenceList(signature: String, kind: String, list: Collection<IDexAddress>?, limit: Int) = Handler.References.Out(
    target = signature,
    type = kind,
    referencesTo = list.orEmpty().take(limit).map { Handler.References.Ref(it.addr) },
    referenceCount = list?.size ?: 0,
)

fun describe(method: IDexMethod, ancestors: List<IDexClass>): Handler.ClassMethods.Method {
    val signature = method.getSignature(false)
    val data = method.data
    val flags = data?.accessFlags ?: method.genericFlags
    val overridden = ancestors.firstNotNullOfOrNull { ancestor ->
        ancestor.methods.orEmpty().firstOrNull { it.subSignature == method.subSignature }?.getSignature(false)
    }

    return Handler.ClassMethods.Method(
        signature = signature,
        declaredIn = method.classType.signature,
        name = method.getName(false),
        subSignature = method.subSignature,
        genericFlags = method.genericFlags,
        accessFlags = flags,
        public = data?.isPublic ?: flags.hasFlag(ICodeItem.FLAG_PUBLIC),
        protected = data?.isProtected ?: flags.hasFlag(ICodeItem.FLAG_PROTECTED),
        private = data?.isPrivate ?: flags.hasFlag(ICodeItem.FLAG_PRIVATE),
        static = data?.isStatic ?: flags.hasFlag(ICodeItem.FLAG_STATIC),
        final = data?.isFinal ?: flags.hasFlag(ICodeItem.FLAG_FINAL),
        abstract = data?.isAbstract ?: flags.hasFlag(ICodeItem.FLAG_ABSTRACT),
        native = data?.isNative ?: flags.hasFlag(ICodeItem.FLAG_NATIVE),
        synthetic = data?.isSynthetic ?: flags.hasFlag(ICodeItem.FLAG_SYNTHETIC),
        constructor = data?.isConstructor ?: flags.hasFlag(ICodeItem.FLAG_CONSTRUCTOR),
        hasCode = data?.codeItem != null,
        overrides = overridden,
    )
}

fun render(fields: Map<String, JsonElement>) = JsonObject(fields).toString()

val IDexMethod.subSignature: String
    get() = getSignature(false).substringAfter("->")

fun Int.hasFlag(flag: Int) = this and flag != 0
