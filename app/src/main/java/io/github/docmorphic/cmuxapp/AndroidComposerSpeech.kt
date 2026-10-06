package io.github.docmorphic.cmuxapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

internal interface ComposerSpeechService {
    val available: Boolean
    val permissionGranted: Boolean
    fun create(): ComposerSpeechEngine
}

/** SpeechRecognizer requires main-thread calls; it owns capture, never app audio files. */
internal class AndroidComposerSpeech(private val context: Context) : ComposerSpeechService {
    private val onDevice get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override val available get() = runCatching { onDevice || SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    override val permissionGranted get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun create(): ComposerSpeechEngine {
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        return object : ComposerSpeechEngine {
            override fun start(listener: ComposerSpeechEngine.Listener) {
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = listener.ready()
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = listener.ended()
                    override fun onError(error: Int) = listener.failed(errorMessage(error))
                    override fun onResults(results: Bundle?) = listener.transcript(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), true)
                    override fun onPartialResults(partialResults: Bundle?) = listener.transcript(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), false)
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
            }
            override fun stop() = recognizer.stopListening()
            override fun close() { try { recognizer.cancel() } finally { recognizer.destroy() } }
        }
    }
    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access in Android Settings to use dictation."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Dictation is unavailable for this language. Check your speech recognition settings and downloaded languages."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech recognized. Try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is busy. Try again shortly."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech recognition lost its connection. Your draft was kept."
        SpeechRecognizer.ERROR_AUDIO -> "Could not access the microphone. Check Android microphone settings."
        else -> "Dictation stopped. Your draft was kept; you can try again."
    }
}
