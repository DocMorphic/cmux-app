package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*

internal fun checkNativeCurrentOwnerReference(text:String):Int {
    val cases=JSONObject(text).getJSONArray("cases");assertEquals(512,cases.length())
    val captured=NativeAuthenticatedOwner(NativeAuthIdentity(3,"account-a"),"login",1)
    for(i in 0 until cases.length()) {
        val row=cases.getJSONObject(i);val input=row.getJSONObject("input")
        val authenticated=input.getBoolean("authenticated");val user=input.opt("user") as? String
        val current=input.getString("role")=="current"
        val transition=nativeApplicationTransition(current,input.getBoolean("validating"),input.getBoolean("restoring"),authenticated,input.getBoolean("capturing"))
        val published=user?.takeIf{authenticated && it.isNotEmpty()}?.let{NativeAuthenticatedOwner(NativeAuthIdentity(input.getLong("generation"),it),"login",1)}
        val snapshots=NativeAuthenticatedSnapshots(read={error("Credential-free check read storage")},storedCaptureBlocked={error("Capture gate consulted")},
            transitionActive={transition},awaitBootstrapped={error("Bootstrap awaited")},accessToken={error("Access resolved")},publishedOwner={published})
        val label="row $i: $input"
        assertEquals(label,row.getBoolean("transition"),transition)
        assertEquals(label,row.getBoolean("current"),snapshots.isCurrent(captured))
    }
    return cases.length()
}
