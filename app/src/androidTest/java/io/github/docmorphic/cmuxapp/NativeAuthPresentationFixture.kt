package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Local-connector screen tests start with explicit cached presentation, never membership proof.
 * The production account/HTTP restore path is exercised separately in NativeSessionRestoreRuntimeTest.
 */
internal fun JSONObject.withNativeAuthPresentationFixture(): JSONObject = apply {
    check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
    val environment=NativeOAuthClient(NativeAccount.PROJECT_ID,NativeAccount.PUBLISHABLE_KEY).accountEnvironment
    NativeAuthUserCache.write(this,environment,NativeSignInUser("emulator-fixture-user","Fixture Person",null,null))
}
