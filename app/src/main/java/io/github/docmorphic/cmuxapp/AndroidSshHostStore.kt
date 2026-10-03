package io.github.docmorphic.cmuxapp

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Metadata only, device-local as on iOS. UI access still requires a signed-in account.
 * One instance per application path serializes mutations within the main process. */
internal object AndroidSshHostStore {
    private val stores = mutableMapOf<String, SshHostStore>()

    @Synchronized fun get(context: Context): SshHostStore {
        val root = File(context.applicationContext.noBackupFilesDir, "ssh")
        val file = AtomicFile(File(root, "hosts-v1.json"))
        return stores.getOrPut(file.baseFile.absolutePath) {
            SshHostStore(read = {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    file.openRead().use {
                        require(it.channel.size() <= SshHostStore.MAX_BYTES)
                        it.readBytes().decodeToString(throwOnInvalidSequence = true)
                    }
                } else null
            }, write = { text ->
                check(root.isDirectory || root.mkdirs()) { "Could not create SSH storage" }
                val output = file.startWrite()
                try {
                    output.write(text.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (failure: Throwable) {
                    file.failWrite(output)
                    throw failure
                }
            })
        }
    }
}
