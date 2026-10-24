package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Test

class NativeSnapshotReferenceTest {
    @Test fun completeOriginalReleaseSnapshotAndCoherentDeclarations()=runBlocking<Unit> {
        checkNativeSnapshotReference(checkNotNull(javaClass.getResource("/auth/native-snapshot-reference.json")).readText())
    }
}
