package com.kfc

import com.pnfsoftware.jeb.client.api.IScript
import com.pnfsoftware.jeb.client.api.IClientContext

class KfcExtension : IScript {

    override fun run(ctx: IClientContext) {
        val engCtx = ctx.enginesContext
        if (engCtx == null) {
            println("[kfc] ERROR: No engines context available.")
            return
        }

        val projects = engCtx.projects
        if (projects.isNullOrEmpty()) {
            println("[kfc] ERROR: No project loaded. Pass an APK/DEX as argument.")
            return
        }

        val port = System.getProperty("kfc.port", "8199").toIntOrNull() ?: 8199
        val project = projects[0]

        println("[kfc] Project: ${project.name}")
        println("[kfc] Starting bridge on port $port ...")

        val bridge = KfcBridge(engCtx, project, port)
        bridge.start()

        println("[kfc] Ready at http://localhost:$port")
        println("[kfc] Press Ctrl+C to stop.")

        try {
            Thread.currentThread().join()
        } catch (_: InterruptedException) {
            bridge.stop()
            println("[kfc] Stopped.")
        }
    }
}
