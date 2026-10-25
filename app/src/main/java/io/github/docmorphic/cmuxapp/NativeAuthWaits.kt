package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Read-only completion joins and structured server tails share a clock, not operation ownership. */
internal class NativeAuthWaits(private val nowMillis:()->Long,private val waitUntil:(suspend(Long)->Unit)?=null) {
    suspend fun completions(jobs:List<Job>,timeoutMillis:Long,absoluteDeadlineMillis:Long?=null) {
        require(timeoutMillis>0)
        currentCoroutineContext().ensureActive()
        if(jobs.all{it.isCompleted})return
        val deadline=absoluteDeadlineMillis ?: (nowMillis()+timeoutMillis)
        // Only the cancellable join watcher belongs to this scope. The actual jobs retain their owners.
        supervisorScope {
            val watcher=async(start=CoroutineStart.LAZY){jobs.forEach{it.join()}}
            race(this,watcher,deadline)
        }
    }

    suspend fun <T> structured(timeoutMillis:Long,operation:suspend()->T):T {
        require(timeoutMillis>0)
        currentCoroutineContext().ensureActive()
        val deadline=nowMillis()+timeoutMillis
        // Unlike a completion watcher, this child IS the operation. Scope exit joins it after cancellation.
        return supervisorScope {race(this,async(start=CoroutineStart.LAZY){operation()},deadline)}
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> race(scope:CoroutineScope,operation:Deferred<T>,deadline:Long):T = suspendCancellableCoroutine {continuation->
        val settled=java.util.concurrent.atomic.AtomicBoolean(false)
        val timer=scope.launch(start=CoroutineStart.LAZY) {
            val failure=try {
                if(waitUntil!=null)waitUntil.invoke(deadline) else delay((deadline-nowMillis()).coerceAtLeast(0))
                ensureActive()
                NativeSignInFailure(NativeSignInError.TIMED_OUT)
            }catch(_:CancellationException){return@launch}
             catch(error:Exception){error}
            if(settled.compareAndSet(false,true)) {
                operation.cancel(if(failure is NativeSignInFailure && failure.reason==NativeSignInError.TIMED_OUT)
                    NativeAuthDeadlineCancellation() else CancellationException("Deadline wait failed"))
                continuation.resumeWith(Result.failure(failure))
            }
        }
        continuation.invokeOnCancellation {
            if(settled.compareAndSet(false,true)) {timer.cancel();operation.cancel()}
        }
        operation.invokeOnCompletion {error->
            if(settled.compareAndSet(false,true)) {
                timer.cancel()
                continuation.resumeWith(if(error!=null)Result.failure(error) else runCatching{operation.getCompleted()})
            }
        }
        // Match the original watcher-first race, without changing its winner during public delivery.
        operation.start();timer.start()
    }
}
