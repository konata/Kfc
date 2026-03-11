package com.kfc

import com.pnfsoftware.jeb.core.Artifact
import com.pnfsoftware.jeb.core.IEnginesContext
import com.pnfsoftware.jeb.core.IRuntimeProject
import com.pnfsoftware.jeb.core.input.FileInput
import com.pnfsoftware.jeb.core.units.IUnit
import com.pnfsoftware.jeb.core.units.IXmlUnit
import com.pnfsoftware.jeb.core.units.code.android.IApkUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit
import kotlinx.serialization.json.*
import java.io.File

class KfcContext(val enginesContext: IEnginesContext) {

    var project: IRuntimeProject? = null
        private set
    var loadedPath: String? = null
        private set

    var allUnits: List<IUnit> = emptyList()
        private set
    var apkUnit: IApkUnit? = null
        private set
    var dexUnits: List<IDexUnit> = emptyList()
        private set
    var primaryDex: IDexUnit? = null
        private set
    var manifestXml: IXmlUnit? = null
        private set

    fun loadProject(apkPath: String): String {
        val file = File(apkPath)
        if (!file.isFile) return """{"error":"File not found: $apkPath"}"""

        val previous = loadedPath

        val prj = enginesContext.loadProject("kfc-${file.nameWithoutExtension}")
        prj.processArtifact(Artifact(file.name, FileInput(file)))

        project = prj
        loadedPath = file.absolutePath
        refreshUnits(prj)

        println("[kfc] Loaded: ${file.absolutePath} (units=${allUnits.size}, dex=${dexUnits.size})")
        return buildJsonObject {
            put("success", true)
            put("path", file.absolutePath)
            put("units", allUnits.size)
            put("dex_count", dexUnits.size)
            previous?.let { put("previous", it) }
        }.toString()
    }


    private fun refreshUnits(prj: IRuntimeProject) {
        allUnits = collectUnits(prj)
        apkUnit = allUnits.filterIsInstance<IApkUnit>().firstOrNull()
        dexUnits = allUnits.filterIsInstance<IDexUnit>()
        primaryDex = dexUnits.firstOrNull()
        manifestXml = allUnits.filterIsInstance<IXmlUnit>().firstOrNull {
            it.name.equals("AndroidManifest.xml", ignoreCase = true)
                || it.name.equals("Manifest", ignoreCase = true)
        }
    }

    fun getDecompiler(dex: IDexUnit): IDexDecompilerUnit? {
        return allUnits.filterIsInstance<IDexDecompilerUnit>().firstOrNull()
            ?: dex.children?.filterIsInstance<IDexDecompilerUnit>()?.firstOrNull()
    }

    private fun collectUnits(project: IRuntimeProject): List<IUnit> {
        val result = mutableListOf<IUnit>()
        fun walk(unit: IUnit) {
            result.add(unit)
            unit.children?.forEach { walk(it) }
        }
        project.liveArtifacts?.forEach { artifact ->
            artifact.units?.forEach { walk(it) }
        }
        return result
    }
}
