package io.github.docmorphic.cmuxapp

import android.os.SystemClock
import android.system.OsConstants
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** CLOCK_BOOTTIME advances through suspend; this timer does not wake the device or use alarms. */
internal object NativeContinuousDeadline {
    private val scheduler=NativeDeadlineScheduler(SystemClock::elapsedRealtime){BootTimeKernel()}
    internal val pendingCount get()=scheduler.pendingCount
    suspend fun await(deadlineMillis:Long)=withContext(Dispatchers.IO) {
        suspendCancellableCoroutine<Unit> {continuation->
            try {
                val registration=scheduler.register(deadlineMillis){result->
                    if(continuation.isActive)continuation.resumeWith(result.fold({Result.success(Unit)},
                        {Result.failure(NativeSignInFailure(NativeSignInError.NETWORK_ERROR))}))
                }
                continuation.invokeOnCancellation{runCatching{registration.close()}}
            }catch(_:Throwable){
                if(continuation.isActive)continuation.resumeWith(Result.failure(NativeSignInFailure(NativeSignInError.NETWORK_ERROR)))
            }
        }
    }

    private interface LibC:Library {
        fun timerfd_create(clock:Int,flags:Int):Int
        fun timerfd_settime(fd:Int,flags:Int,value:Pointer,previous:Pointer?):Int
        fun eventfd(initial:Int,flags:Int):Int
        fun poll(fds:Pointer,count:Long,timeout:Int):Int
        fun read(fd:Int,buffer:Pointer,size:Long):Long
        fun write(fd:Int,buffer:Pointer,size:Long):Long
        fun close(fd:Int):Int
    }
    private val libc:LibC by lazy {Native.load("c",LibC::class.java)}
    private class BootTimeKernel:NativeDeadlineKernel {
        private var timer=-1;private var wake=-1
        private var closed=false
        private val poll=Memory(16);private val value=Memory(32);private val counter=Memory(8)
        init {
            try {
                // The app ships arm64 only. LP64 timespec/itimerspec and pollfd layouts are explicit.
                check(Native.LONG_SIZE==8 && Native.POINTER_SIZE==8)
                timer=libc.timerfd_create(7,OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK);check(timer>=0)
                wake=libc.eventfd(0,OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK);check(wake>=0)
                poll.clear();poll.setInt(0,timer);poll.setShort(4,OsConstants.POLLIN.toShort())
                poll.setInt(8,wake);poll.setShort(12,OsConstants.POLLIN.toShort())
            }catch(error:Throwable){close();throw error}
        }
        override fun arm(deadlineMillis:Long) {
            value.clear();value.setLong(16,deadlineMillis.coerceAtLeast(1)/1000)
            value.setLong(24,(deadlineMillis.coerceAtLeast(1)%1000)*1_000_000)
            check(libc.timerfd_settime(timer,1,value,null)==0)
        }
        override fun signal() {
            // A separate buffer avoids racing the worker's eventfd/timerfd reads.
            Memory(8).use {one->one.setLong(0,1)
                while(libc.write(wake,one,8)<0)when(Native.getLastError()) {
                    OsConstants.EINTR->continue
                    OsConstants.EAGAIN->return
                    else->error("Deadline wake failed")
                }
            }
        }
        override fun awaitEvent() {
            var count:Int
            do {count=libc.poll(poll,2,-1)}while(count<0 && Native.getLastError()==OsConstants.EINTR)
            check(count>0)
            for(offset in listOf(0L,8L)) {
                val events=poll.getShort(offset+6).toInt()
                check(events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)==0)
                if(events and OsConstants.POLLIN!=0) {
                    while(libc.read(poll.getInt(offset),counter,8)<0)when(Native.getLastError()) {
                        OsConstants.EINTR->continue
                        OsConstants.EAGAIN->break
                        else->error("Deadline event read failed")
                    }
                }
            }
        }
        override fun close() {
            if(closed)return
            closed=true
            if(timer>=0){libc.close(timer);timer=-1}
            if(wake>=0){libc.close(wake);wake=-1}
            poll.close();value.close();counter.close()
        }
    }
}
