package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class SshCmuxSocket(val name: String?, val digest: String, val path: String) {
    fun serves(session: String): Boolean = if (name != null) name == session else digest == SshCmuxDiscovery.digest(session)
}

internal object SshCmuxDiscovery {
    // Keep the owner's precedence, including macOS's per-user temporary
    // directory, which is often absent from an SSH login's environment.
    val socketScript = """
        u=${'$'}(id -u)
        for d in "${'$'}{XDG_RUNTIME_DIR:-}" "${'$'}(getconf DARWIN_USER_TEMP_DIR 2>/dev/null)" "${'$'}{TMPDIR:-}" /tmp; do
          [ -n "${'$'}d" ] || continue
          for k in "cmux-tui-${'$'}u" "cmux-tui-hashed-${'$'}u"; do
            s="${'$'}{d%/}/${'$'}k"
            [ -d "${'$'}s" ] || continue
            for f in "${'$'}s"/*.sock; do [ -S "${'$'}f" ] && printf '%s\n' "${'$'}f"; done
          done
        done
        exit 0
    """.trimIndent()
    val binaryScript = """
        for p in "${'$'}HOME/.local/bin/cmux-tui" "${'$'}(command -v cmux-tui 2>/dev/null)" /opt/homebrew/bin/cmux-tui /usr/local/bin/cmux-tui; do
          [ -n "${'$'}p" ] && [ -x "${'$'}p" ] && [ ! -d "${'$'}p" ] && { printf '%s\n' "${'$'}p"; exit 0; }
        done
        exit 1
    """.trimIndent()
    fun digest(session: String): String = MessageDigest.getInstance("SHA-256").digest(session.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun validSession(session: String) = session.isNotEmpty() && session != "." && session != ".." &&
        session.none { it == '/' || it == '\\' || it.isISOControl() || it == '\u2028' || it == '\u2029' }
    fun validPath(path: String) = path.startsWith('/') && path.none { it.isISOControl() || it == '\u2028' || it == '\u2029' } &&
        path.split('/').none { it == "." || it == ".." }
    fun parseSockets(output: String): List<SshCmuxSocket> {
        require(output.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "cmux-tui socket listing is too large" }
        val sockets = linkedMapOf<String, SshCmuxSocket>()
        for (path in output.split('\n')) {
            if (!validPath(path) || !path.endsWith(".sock")) continue
            val directory = path.substringBeforeLast('/').substringAfterLast('/')
            val stem = path.substringAfterLast('/').removeSuffix(".sock")
            val hashed = Regex("cmux-tui-hashed-[0-9]+").matches(directory)
            val socket = when {
                hashed && Regex("[0-9a-f]{64}").matches(stem) -> SshCmuxSocket(null, stem, path)
                !hashed && Regex("cmux-tui-[0-9]+").matches(directory) && validSession(stem) -> SshCmuxSocket(stem, digest(stem), path)
                else -> continue
            }
            sockets.putIfAbsent(socket.digest, socket)
            require(sockets.size <= 128) { "Too many cmux-tui session sockets" }
        }
        return sockets.values.toList()
    }
    fun shell(script: String) = "sh -c " + SshTmuxEncoding.shellQuote(script)
    fun relayCommand(binary: String, socket: SshCmuxSocket): String {
        require(validPath(binary) && validPath(socket.path))
        require(parseSockets(socket.path).singleOrNull() == socket) { "Invalid cmux-tui socket descriptor" }
        // --socket never starts a new owner. All paths are positional, quoted
        // arguments; remote session names are never interpolated as shell code.
        return "exec " + listOf(binary, "relay", "--socket", socket.path).joinToString(" ", transform = SshTmuxEncoding::shellQuote)
    }
}

/** Existing-owner discovery over the current account's SSH route. All exec and
 * stream lifetimes belong to SshTransport; this never installs or starts a server.
 * Function adapters also let the real-process test exercise these exact commands. */
internal class SshCmuxRemote(private val exec: suspend (String) -> SshExecResult,
    private val open: suspend (String) -> SshExecPipe, private val lifetime: CoroutineScope) {
    constructor(connection: SshTransport, lifetime: CoroutineScope) : this(
        { connection.exec(it, maxOutputBytes = 1024 * 1024) }, { connection.openExecStream(it) }, lifetime)

    private fun text(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    suspend fun probePlatform(): SshCmuxPlatform {
        val result = exec(SshCmuxDiscovery.shell(SshCmuxPlatform.SCRIPT))
        check(result.exitStatus == 0) { "Could not determine this computer's platform" }
        return SshCmuxPlatform.parse(text(result.stdout))
    }
    suspend fun connectOwned(binary: String): SshCmuxControl {
        require(SshCmuxDiscovery.validPath(binary))
        val quoted = SshTmuxEncoding.shellQuote(binary)
        // Only this product-scoped owner may be started by the phone. Existing
        // desktop sessions continue to use relay --socket without ensure.
        val script = "$quoted server ensure --session cmux-android --json >&2 && exec $quoted relay --session cmux-android"
        var control: SshCmuxControl? = null
        return try { withTimeout(30000) {
            SshCmuxControl(open(SshCmuxDiscovery.shell(script)), lifetime).also {
                control = it; it.handshake("cmux-android")
            }
        } } catch (failure: Exception) { control?.close(); throw failure }
    }
    suspend fun locateBinary(): String? {
        val result = exec(SshCmuxDiscovery.shell(SshCmuxDiscovery.binaryScript))
        if (result.exitStatus == 1) return null
        check(result.exitStatus == 0) { "Could not discover cmux-tui executable" }
        // Remove the script's one LF, not arbitrary path whitespace.
        val path = text(result.stdout).removeSuffix("\n")
        check(SshCmuxDiscovery.validPath(path) && !path.endsWith('/')) { "Invalid cmux-tui executable path" }
        return path
    }
    suspend fun listSockets(): List<SshCmuxSocket> {
        val result = exec(SshCmuxDiscovery.shell(SshCmuxDiscovery.socketScript))
        check(result.exitStatus == 0) { "Could not discover cmux-tui sessions" }
        return SshCmuxDiscovery.parseSockets(text(result.stdout))
    }
    suspend fun connect(binary: String, socket: SshCmuxSocket): SshCmuxControl {
        var control: SshCmuxControl? = null
        return try { withTimeout(10000) {
            val client = SshCmuxControl(open(SshCmuxDiscovery.relayCommand(binary, socket)), lifetime)
            control = client
            val info = client.handshake(socket.name)
            check(SshCmuxDiscovery.validSession(info.session) && socket.serves(info.session)) { "cmux-tui socket identity changed" }
            client
        } } catch (failure: Exception) { control?.close(); throw failure }
    }
}
