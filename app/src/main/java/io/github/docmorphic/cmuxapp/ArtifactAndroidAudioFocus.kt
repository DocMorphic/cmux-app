package io.github.docmorphic.cmuxapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal class ArtifactAndroidAudioFocus(context: Context, private val attributes: AudioAttributes) : ArtifactAudioFocusPort {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    override fun request(listener: (ArtifactFocusEvent) -> Unit): Boolean {
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> listener(ArtifactFocusEvent.GAIN)
                    AudioManager.AUDIOFOCUS_LOSS -> listener(ArtifactFocusEvent.LOSS)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener(ArtifactFocusEvent.TRANSIENT_LOSS)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener(ArtifactFocusEvent.DUCK)
                }
            }, Handler(Looper.getMainLooper())).build()
        request = next
        return runCatching { manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
    }
    override fun abandon() {
        val previous = request ?: return
        request = null
        manager.abandonAudioFocusRequest(previous)
    }
}
