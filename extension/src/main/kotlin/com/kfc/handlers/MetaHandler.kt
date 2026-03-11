package com.kfc.handlers

import com.kfc.KfcContext
import com.pnfsoftware.jeb.core.units.IXmlUnit
import kotlinx.serialization.json.*

object MetaHandler {

    fun projectInfo(ctx: KfcContext): String {
        val prj = ctx.project
        return buildJsonObject {
            put("project_name", prj.name)
            put("apk_detected", ctx.apkUnit != null)
            put("dex_count", ctx.dexUnits.size)
            put("total_units", ctx.allUnits.size)
            putJsonArray("artifacts") {
                prj.liveArtifacts?.forEach { artifact ->
                    add(buildJsonObject {
                        put("name", artifact.artifact?.name ?: "unknown")
                    })
                }
            }
        }.toString()
    }

    fun manifest(ctx: KfcContext): String {
        val xml = ctx.manifestXml
            ?: return """{"error":"AndroidManifest.xml not found"}"""
        val content = xmlToString(xml)
        return buildJsonObject {
            put("content", content)
        }.toString()
    }

    fun listUnits(ctx: KfcContext): String {
        return buildJsonArray {
            ctx.allUnits.forEach { unit ->
                add(buildJsonObject {
                    put("name", unit.name)
                    put("type", unit.javaClass.simpleName)
                    put("description", unit.description ?: "")
                    put("status", unit.status ?: "")
                })
            }
        }.toString()
    }

    fun permissions(ctx: KfcContext): String {
        val xml = ctx.manifestXml ?: return """{"permissions":[]}"""
        val content = xmlToString(xml)
        val perms = mutableListOf<String>()
        val regex = Regex("""android:name="(android\.permission\.[^"]+)"""")
        regex.findAll(content).forEach { match ->
            perms.add(match.groupValues[1])
        }
        return buildJsonObject {
            putJsonArray("permissions") { perms.forEach { add(it) } }
        }.toString()
    }

    fun components(ctx: KfcContext): String {
        val xml = ctx.manifestXml
            ?: return """{"activities":[],"services":[],"receivers":[],"providers":[]}"""
        val content = xmlToString(xml)

        fun extractComponents(tagName: String): List<String> {
            val results = mutableListOf<String>()
            val regex = Regex("""<$tagName[^>]*android:name="([^"]+)"""")
            regex.findAll(content).forEach { match ->
                results.add(match.groupValues[1])
            }
            return results
        }

        return buildJsonObject {
            putJsonArray("activities") { extractComponents("activity").forEach { add(it) } }
            putJsonArray("services") { extractComponents("service").forEach { add(it) } }
            putJsonArray("receivers") { extractComponents("receiver").forEach { add(it) } }
            putJsonArray("providers") { extractComponents("provider").forEach { add(it) } }
        }.toString()
    }

    private fun xmlToString(xmlUnit: IXmlUnit): String {
        return try {
            val sw = java.io.StringWriter()
            val tf = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
            tf.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
            tf.transform(
                javax.xml.transform.dom.DOMSource(xmlUnit.document),
                javax.xml.transform.stream.StreamResult(sw)
            )
            sw.toString()
        } catch (e: Exception) {
            "(failed to serialize XML: ${e.message})"
        }
    }
}
