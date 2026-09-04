package com.gatherin.utils

import android.media.AudioManager
import android.media.ToneGenerator

class AudioService {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    fun playSuccess() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
    }

    fun playDuplicate() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
    }

    fun playError() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 500)
    }

    fun playQueued() {
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 100)
    }

    fun release() {
        toneGenerator.release()
    }
}
