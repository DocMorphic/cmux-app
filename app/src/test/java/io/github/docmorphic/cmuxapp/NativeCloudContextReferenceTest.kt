package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Test

class NativeCloudContextReferenceTest {
    @Test fun completeOriginalCoherentContextAndCurrentTokens()=runBlocking<Unit> {
        checkNativeCloudContextReference(checkNotNull(javaClass.getResource("/auth/native-cloud-context-reference.json")).readText())
    }
}
