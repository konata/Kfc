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

        val port = System.getProperty("kfc.port", "9527").toIntOrNull() ?: 9527
        val kfcCtx = KfcContext(engCtx)

        println("[kfc] Starting bridge on port $port ...")
        val bridge = KfcBridge(kfcCtx, port)
        bridge.start()

        println("[kfc] Ready at http://localhost:$port")
        println("[kfc] Waiting for load_apk ...")

        try {
            Thread.currentThread().join()
        } catch (_: InterruptedException) {
            bridge.stop()
            println("[kfc] Stopped.")
        }
    }
}
