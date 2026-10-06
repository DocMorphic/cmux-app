package io.github.docmorphic.cmuxapp.iroh

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.SecretKey
import computer.iroh.presetMinimal
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class IrohInstallationStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-" + UUID.randomUUID()
    private val directory get() = File(context.noBackupFilesDir, "cmux-iroh-v2/$name")
    private val scope get() = IrohAccountScope("test", "project", "team", "user", context.packageName, "debug")
    private fun store() = IrohInstallationStore.isolatedTestStore(context, name)

    @Before fun initialize() = IrohRuntime.initialize(context)
    @After fun cleanUp() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.cmux-iroh-v2.$name") }
    }

    @Test fun restoredIdentitySignsAndBindsTheSameNativeEndpoint() = runBlocking {
        val first = store().loadOrCreate(scope)
        val expected = first.endpointId
        val installation = first.deviceId
        val message = "installation proof".toByteArray()
        val signature = first.sign(message)
        first.close()
        assertThrows(IllegalStateException::class.java) { first.sign(message) }
        store().loadOrCreate(scope).use { restored ->
            assertEquals(expected, restored.endpointId)
            assertEquals(installation, restored.deviceId)
            assertArrayEquals(signature, restored.sign(message))
            val seed = restored.seedForEndpoint()
            try {
                assertTrue(directory.listFiles()!!.filter { it.name.endsWith(".seed") }
                    .all { file -> !file.readBytes().toList().windowed(seed.size).any { it == seed.toList() } })
                SecretKey.fromBytes(seed).use { secret -> secret.`public`().use { public ->
                    secret.sign(message).use { signed -> public.verify(message, signed) }
                } }
                val options = EndpointOptions(preset = presetMinimal(), secretKey = seed,
                    bindAddr = "127.0.0.1:0", portMappingEnabled = false,
                    deferNatTraversalUntilAuthorized = true, initialMaxConcurrentBiStreams = 0uL,
                    initialMaxConcurrentUniStreams = 0uL)
                val endpoint = Endpoint.bind(options)
                try {
                    endpoint.id().use { id -> assertEquals(expected, id.toBytes().joinToString("") { "%02x".format(it) }) }
                } finally {
                    try { withContext(NonCancellable) { withTimeout(5_000) { endpoint.shutdown() } } }
                    finally { endpoint.close(); options.destroy() }
                }
            } finally { seed.fill(0) }
        }
    }

    @Test fun allAccountScopeFieldsIsolateKeysAndConcurrentLoadsConverge() {
        val variants = listOf(scope, scope.copy(environment = "production"), scope.copy(projectId = "other-project"),
            scope.copy(teamId = "other-team"), scope.copy(userId = "other-user"), scope.copy(buildTag = "release"))
        val identities = variants.map { variant -> store().loadOrCreate(variant).use { it.endpointId to it.deviceId } }
        assertEquals(variants.size, identities.map { it.first }.distinct().size)
        assertEquals(1, identities.map { it.second }.distinct().size)
        assertThrows(IllegalArgumentException::class.java) { store().loadOrCreate(scope.copy(appNamespace = "other.app")) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val ids = executor.invokeAll(List(8) { Callable { store().loadOrCreate(scope).use { it.endpointId } } })
                .map { it.get() }
            assertEquals(setOf(identities.first().first), ids.toSet())
        } finally { executor.shutdownNow() }
    }

    @Test fun ciphertextCannotMoveAcrossScopesAndTamperingDoesNotReplaceIdentity() {
        store().loadOrCreate(scope).close()
        val first = directory.listFiles()!!.single { it.name.endsWith(".seed") }
        store().loadOrCreate(scope.copy(teamId = "another-team")).close()
        val second = directory.listFiles()!!.single { it.name.endsWith(".seed") && it != first }
        val originalSecond = second.readBytes()
        second.writeBytes(first.readBytes())
        assertThrows(IOException::class.java) { store().loadOrCreate(scope.copy(teamId = "another-team")) }
        assertArrayEquals(first.readBytes(), second.readBytes())
        second.writeBytes(originalSecond)
        val damaged = first.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        first.writeBytes(damaged)
        assertThrows(IOException::class.java) { store().loadOrCreate(scope) }
        assertArrayEquals(damaged, first.readBytes())
        store().loadOrCreate(scope.copy(teamId = "another-team")).close()
    }

    @Test fun missingWrappingKeyOrInstallationIdDoesNotSilentlyReenroll() {
        store().loadOrCreate(scope).close()
        val record = directory.listFiles()!!.single { it.name.endsWith(".seed") }
        val bytes = record.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.cmux-iroh-v2.$name") }
        assertThrows(IOException::class.java) { store().loadOrCreate(scope) }
        assertArrayEquals(bytes, record.readBytes())
        File(directory, "installation-id").delete()
        assertThrows(IOException::class.java) { store().loadOrCreate(scope) }
        assertFalse(File(directory, "installation-id").exists())
        assertArrayEquals(bytes, record.readBytes())
    }

    @Test fun cloudRegistryReadDoesNotMintOrReplaceAnInstallation() {
        assertNull(store().storedDeviceId())
        assertFalse(directory.exists())
        val expected = store().loadOrCreate(scope).use { it.deviceId }
        assertEquals(expected, store().storedDeviceId())
        val id = File(directory, "installation-id")
        id.writeText("damaged")
        assertThrows(IOException::class.java) { store().storedDeviceId() }
        assertEquals("damaged", id.readText())
        id.delete()
        assertThrows(IOException::class.java) { store().storedDeviceId() }
        assertFalse(id.exists())
    }
}
