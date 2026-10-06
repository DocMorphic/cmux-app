package io.github.docmorphic.cmuxapp

/** UI wiring fixture: never creates a recognizer, requests permission, or records audio. */
internal class FakeComposerSpeech : ComposerSpeechService {
    override val available = true
    override val permissionGranted = true
    val engines = mutableListOf<Engine>()
    override fun create() = Engine().also { engines += it }
    class Engine : ComposerSpeechEngine {
        lateinit var listener: ComposerSpeechEngine.Listener
        var closed = false
        var stopped = false
        override fun start(listener: ComposerSpeechEngine.Listener) { this.listener = listener; listener.ready() }
        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }
}
