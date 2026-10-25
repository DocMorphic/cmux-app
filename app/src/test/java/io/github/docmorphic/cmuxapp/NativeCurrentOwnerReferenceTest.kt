package io.github.docmorphic.cmuxapp

import org.junit.Test

class NativeCurrentOwnerReferenceTest {
    @Test fun completeOriginalCurrentOwnerDeclarationsMatchWithoutCredentialReads() {
        val text=javaClass.classLoader!!.getResourceAsStream("auth/native-current-owner-reference.json")!!.bufferedReader().use{it.readText()}
        checkNativeCurrentOwnerReference(text)
    }
}
