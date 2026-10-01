package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class SshCmuxPlatform(val os: String, val arch: String) {
    val packageName: String? get() {
        val system = when (os.lowercase(Locale.ROOT)) { "linux" -> "linux"; "darwin" -> "darwin"; else -> return null }
        val cpu = when (arch.lowercase(Locale.ROOT)) { "x86_64", "amd64" -> "x64"; "arm64", "aarch64" -> "arm64"; else -> return null }
        return "cmux-tui-$system-$cpu"
    }
    companion object {
        const val SCRIPT = "uname -s && uname -m"
        fun parse(output: String): SshCmuxPlatform {
            val rows = output.removeSuffix("\n").split('\n')
            require(rows.size == 2 && rows.all { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) }) { "Invalid SSH platform response" }
            return SshCmuxPlatform(rows[0], rows[1])
        }
    }
}

/** Official npm platform artifacts, reviewed as one release. A registry change
 * cannot silently replace the executable pinned by this application build. */
internal object SshCmuxRelease {
    const val VERSION = "0.13.4"
    const val MAX_ARCHIVE = 64L * 1024 * 1024
    val integrity = mapOf(
        "cmux-tui-darwin-arm64" to "O0N+CffNanx9DvdAJc6J7FgqtEitY+E7FNEkvJfXs0QeAcvfAb5jhROqkdw4IsSGcJlwF8NNa/8ak4p3nSyGkw==",
        "cmux-tui-darwin-x64" to "jNbjhV/blp+DAKYLbbXKp5xxfi6RocNyjZZuK0M5akFMNxkSg64Si0Nf1tQEj9DipIvUeOzHyzBogDUdYMd0aQ==",
        "cmux-tui-linux-arm64" to "Qb0VWOW+eY60SYOV5mxbpUnPQEHLpd1J+gjUQIO4q7KLCSG9Y9wo7AQlp7qU0Dg/ArNlDlVLw0zvMIZcjRPyCQ==",
        "cmux-tui-linux-x64" to "YH2wIm5yFk3iymg49qO0ns923a+ei1RHu8gWVJlKFM56W/TDEUXph+tHI59pzlj/vz2pbaWnn6FNBhXAg0us0w==")
    fun url(packageName: String): String {
        require(packageName in integrity) { "Unsupported cmux-tui platform" }
        return "https://registry.npmjs.org/$packageName/-/$packageName-$VERSION.tgz"
    }
    fun verify(file: File, expected: String) {
        require(file.isFile && file.length() in 1..MAX_ARCHIVE) { "Invalid cmux-tui archive size" }
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536); var count = 0L
            while (true) { val n = stream.read(buffer); if (n < 0) break
                count += n; require(count <= MAX_ARCHIVE) { "cmux-tui archive exceeded its limit" }; digest.update(buffer, 0, n)
            }
        }
        require(MessageDigest.isEqual(digest.digest(), Base64.getDecoder().decode(expected))) { "cmux-tui download checksum did not match" }
    }
}

internal class SshCmuxArchive(private val cache: File,
    private val fetch: suspend (String, File) -> Unit = ::download,
) {
    private val mutex = Mutex()
    suspend fun get(platform: SshCmuxPlatform): File = mutex.withLock {
        val name = checkNotNull(platform.packageName) { "cmux-tui is unavailable for ${platform.os} / ${platform.arch}" }
        val expected = checkNotNull(SshCmuxRelease.integrity[name])
        val cached = File(cache, "$name-${SshCmuxRelease.VERSION}.tgz")
        withContext(Dispatchers.IO) {
            if (cached.exists()) {
                if (runCatching { SshCmuxRelease.verify(cached, expected) }.isSuccess) return@withContext cached
                check(cached.delete()) { "Could not replace invalid cmux-tui cache" }
            }
            check(cache.isDirectory || cache.mkdirs()) { "Could not create cmux-tui download cache" }
            val temporary = File.createTempFile("download-", ".part", cache)
            try {
                fetch(SshCmuxRelease.url(name), temporary)
                ensureActive(); SshCmuxRelease.verify(temporary, expected); ensureActive()
                check(temporary.renameTo(cached)) { "Could not save verified cmux-tui download" }
                cached
            } finally { temporary.delete() }
        }
    }
    companion object {
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
        private suspend fun download(url: String, file: File): Unit = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            check(response.code == 200) { "Could not download cmux-tui (HTTP ${response.code})" }
                            val body = checkNotNull(response.body)
                            check(body.contentLength() <= SshCmuxRelease.MAX_ARCHIVE) { "cmux-tui download exceeded its limit" }
                            body.byteStream().use { input -> file.outputStream().use { output ->
                                val buffer = ByteArray(65536); var count = 0L
                                while (true) {
                                    check(continuation.isActive) { "cmux-tui download canceled" }
                                    val n = input.read(buffer); if (n < 0) break
                                    count += n; check(count <= SshCmuxRelease.MAX_ARCHIVE) { "cmux-tui download exceeded its limit" }
                                    output.write(buffer, 0, n)
                                }
                            } }
                        }
                        if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                        else file.delete() // Cancellation may race output-stream creation.
                    }
                }
            })
        }
    }
}

/** No install is performed during discovery. Upload and activation are scoped
 * to one explicit creation on one authenticated SSH transport. */
internal class SshCmuxInstaller(private val archives: SshCmuxArchive) {
    suspend fun install(platform: SshCmuxPlatform, connection: SshTransport, progress: (String) -> Unit): String =
        install(platform, { connection.exec(it, timeoutMillis = 30000, maxOutputBytes = 64 * 1024) },
            { local, remote -> connection.withSftp { channel -> local.inputStream().use { channel.put(it, remote) } } }, progress)

    internal suspend fun install(platform: SshCmuxPlatform, exec: suspend (String) -> SshExecResult,
        upload: suspend (File, String) -> Unit, progress: (String) -> Unit): String {
        check(platform.packageName != null) { "cmux-tui is unavailable for ${platform.os} / ${platform.arch}" }
        progress("Downloading cmux-tui…")
        val archive = archives.get(platform)
        currentCoroutineContext().ensureActive()
        val nonce = UUID.randomUUID().toString()
        val prepared = exec(SshCmuxDiscovery.shell(SshCmuxInstallScripts.prepare(nonce)))
        check(prepared.exitStatus == 0) { "Could not prepare cmux-tui installation" }
        val stage = prepared.stdout.toString(Charsets.UTF_8).removeSuffix("\n")
        require(SshCmuxInstallScripts.validStage(stage, nonce)) { "Invalid cmux-tui installation directory" }
        try {
            progress("Uploading cmux-tui…")
            withTimeout(120000) { upload(archive, "$stage/release.tgz") }
            currentCoroutineContext().ensureActive()
            progress("Verifying cmux-tui…")
            val probe = exec(SshCmuxDiscovery.shell(SshCmuxInstallScripts.extract(stage, nonce)))
            check(probe.exitStatus == 0) { "Could not unpack or run cmux-tui on this computer" }
            val version = JSONObject(probe.stdout.toString(Charsets.UTF_8)).optString("distribution_version")
            check(version == SshCmuxRelease.VERSION) { "Installed cmux-tui version did not match the verified download" }
            currentCoroutineContext().ensureActive()
            val commit = exec(SshCmuxDiscovery.shell(SshCmuxInstallScripts.commit(stage, nonce)))
            check(commit.exitStatus == 0) {
                if (commit.exitStatus == 73) "A cmux-tui executable appeared during installation. Refresh before retrying."
                else "Could not activate cmux-tui. Refresh to check whether installation completed."
            }
            val path = commit.stdout.toString(Charsets.UTF_8).removeSuffix("\n")
            check(path == stage.substringBeforeLast('/') + "/cmux-tui") { "Invalid installed cmux-tui path" }
            return path
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { exec(SshCmuxDiscovery.shell(SshCmuxInstallScripts.cleanup(stage, nonce))) } } }
        }
    }
}

internal object SshCmuxInstallScripts {
    private fun nonce(value: String) { require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) }
    fun validStage(stage: String, token: String): Boolean {
        nonce(token)
        return SshCmuxDiscovery.validPath(stage) && stage.substringBeforeLast('/').endsWith("/.local/bin") &&
            Regex("\\.cmux-android-install-${Regex.escape(token)}\\.[A-Za-z0-9]{8}").matches(stage.substringAfterLast('/'))
    }
    fun prepare(token: String): String {
        nonce(token)
        return """
            set -eu; umask 077
            b="${'$'}HOME/.local/bin"; mkdir -p "${'$'}b"
            mktemp -d "${'$'}b/.cmux-android-install-$token.XXXXXXXX"
        """.trimIndent()
    }
    private fun prefix(stage: String, token: String): String {
        require(validStage(stage, token))
        return "set -eu; d=${SshTmuxEncoding.shellQuote(stage)}; [ -d \"${'$'}d\" ] && [ ! -L \"${'$'}d\" ]; "
    }
    fun extract(stage: String, token: String) = prefix(stage, token) + """
        tar -xzf "${'$'}d/release.tgz" -C "${'$'}d" package/bin/cmux-tui
        p="${'$'}d/package/bin/cmux-tui"
        [ -f "${'$'}p" ] && [ ! -L "${'$'}p" ]; chmod 755 "${'$'}p"
        "${'$'}p" remote-probe --json
    """.trimIndent()
    fun commit(stage: String, token: String) = prefix(stage, token) + """
        p="${'$'}d/package/bin/cmux-tui"; b="${'$'}{d%/*}/cmux-tui"
        [ -f "${'$'}p" ] && [ ! -L "${'$'}p" ] || exit 74
        [ ! -e "${'$'}b" ] && [ ! -L "${'$'}b" ] || exit 73
        ln "${'$'}p" "${'$'}b" || exit 73
        printf '%s\n' "${'$'}b"
    """.trimIndent()
    fun cleanup(stage: String, token: String) = prefix(stage, token) + "rm -rf -- \"${'$'}d\""
}
