package com.kfc

import com.pnfsoftware.jeb.core.IEnginesContext
import com.pnfsoftware.jeb.core.IRuntimeProject
import com.pnfsoftware.jeb.core.units.IUnit
import com.pnfsoftware.jeb.core.units.IXmlUnit
import com.pnfsoftware.jeb.core.units.code.android.IApkUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit

class KfcContext(
    val enginesContext: IEnginesContext,
    val project: IRuntimeProject,
) {
    val allUnits: List<IUnit> by lazy { collectUnits(project) }

    val apkUnit: IApkUnit? by lazy {
        allUnits.filterIsInstance<IApkUnit>().firstOrNull()
    }

    val dexUnits: List<IDexUnit> by lazy {
        allUnits.filterIsInstance<IDexUnit>()
    }

    val primaryDex: IDexUnit? by lazy { dexUnits.firstOrNull() }

    val manifestXml: IXmlUnit? by lazy {
        allUnits.filterIsInstance<IXmlUnit>().firstOrNull {
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
