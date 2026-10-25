package io.github.docmorphic.cmuxapp

/** One process-owned kernel wait serves all auth deadlines; no account or credential data. */
internal interface NativeDeadlineKernel : AutoCloseable {
    fun arm(deadlineMillis:Long)
    fun signal()
    fun awaitEvent()
}

internal class NativeDeadlineScheduler(private val nowMillis:()->Long,private val open:()->NativeDeadlineKernel) {
    private val lock=Any()
    private data class Wait(val deadline:Long,val complete:(Result<Unit>)->Unit)
    private inner class Worker(val kernel:NativeDeadlineKernel) {
        val waits=linkedMapOf<Any,Wait>()
        fun run() {
            var failure:Throwable?=null
            try {
                while(true) {
                    val ready=synchronized(lock) {
                        val now=nowMillis()
                        waits.filterValues{it.deadline<=now}.toList().also{rows->rows.forEach{waits.remove(it.first)}}
                    }
                    ready.forEach{runCatching{it.second.complete(Result.success(Unit))}}
                    synchronized(lock) {
                        if(waits.isEmpty()) {
                            if(worker===this)worker=null
                            kernel.close();return
                        }
                        kernel.arm(waits.values.minOf{it.deadline})
                    }
                    kernel.awaitEvent()
                }
            }catch(error:Throwable){failure=error}
            finally {
                val pending=synchronized(lock) {
                    if(worker===this)worker=null
                    val pending=waits.values.toList();waits.clear();runCatching{kernel.close()};pending
                }
                val error=failure ?: IllegalStateException("Deadline scheduler retired")
                pending.forEach{runCatching{it.complete(Result.failure(error))}}
            }
        }
    }
    private var worker:Worker?=null
    internal val pendingCount get()=synchronized(lock){worker?.waits?.size ?: 0}

    fun register(deadlineMillis:Long,complete:(Result<Unit>)->Unit):AutoCloseable {
        val key=Any()
        val owner=synchronized(lock) {
            val previous=worker
            val next=previous ?: Worker(open()).also{worker=it}
            try {
                next.waits[key]=Wait(deadlineMillis,complete)
                next.kernel.signal()
                if(previous==null)Thread({next.run()},"cmux-auth-deadlines").apply{isDaemon=true}.start()
                next
            }catch(error:Throwable){
                next.waits.remove(key)
                if(previous==null){worker=null;runCatching{next.kernel.close()}}
                throw error
            }
        }
        return AutoCloseable {synchronized(lock){if(owner.waits.remove(key)!=null)owner.kernel.signal()}}
    }
}
