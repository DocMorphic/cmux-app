package io.github.docmorphic.cmuxapp.iroh

import android.content.Context
import computer.iroh.IrohAndroid

/** Installs the process-wide Android context before creating any Iroh endpoint. */
object IrohRuntime {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        IrohAndroid.installAndroidContext(context.applicationContext)
        initialized = true
    }
}
