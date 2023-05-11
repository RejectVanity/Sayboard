package com.elishaazaria.sayboard.ime

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.ime.recognizers.RecognizerSource
import com.elishaazaria.sayboard.ime.recognizers.providers.RecognizerSourceProvider
import com.elishaazaria.sayboard.ime.recognizers.providers.VoskLocalProvider
import com.elishaazaria.sayboard.ime.recognizers.providers.VoskServerProvider
import com.elishaazaria.sayboard.preferences.LogicPreferences.isListenImmediately
import com.elishaazaria.sayboard.preferences.LogicPreferences.isWeakRefModel
import com.elishaazaria.sayboard.preferences.ModelPreferences
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ModelManager(private val ime: IME, private val viewManager: ViewManager) {
    private var speechService: MySpeechService? = null
    var isRunning = false
        private set
    private val sourceProviders: MutableList<RecognizerSourceProvider> = ArrayList()
    private var recognizerSources: MutableList<RecognizerSource> = ArrayList()
    private var currentRecognizerSourceIndex = 0
    private lateinit var currentRecognizerSource: RecognizerSource
    private val executor: Executor = Executors.newSingleThreadExecutor()
    fun initializeRecognizer() {
        if (recognizerSources.size == 0) return
        val onLoaded = Observer { r: RecognizerSource? ->
            if (isListenImmediately) {
                start() // execute after initialize
            }
        }
        currentRecognizerSource = recognizerSources[currentRecognizerSourceIndex]
        viewManager.recognizerNameLD.postValue(currentRecognizerSource.name)
        currentRecognizerSource.stateLD.observe(ime, viewManager)
        currentRecognizerSource.initialize(executor, onLoaded)
    }

    private fun stopRecognizerSource() {
        currentRecognizerSource.close(isWeakRefModel)
        currentRecognizerSource.stateLD.removeObserver(viewManager)
    }

    fun switchToNextRecognizer() {
        if (recognizerSources.size == 0) return
        stopRecognizerSource()
        currentRecognizerSourceIndex++
        if (currentRecognizerSourceIndex >= recognizerSources.size) {
            currentRecognizerSourceIndex = 0
        }
        initializeRecognizer()
    }

    fun start() {
        if (isRunning || speechService != null) {
            speechService!!.stop()
        }
        viewManager.stateLD.postValue(ViewManager.Companion.STATE_LISTENING)
        try {
            val recognizer = currentRecognizerSource.recognizer
            if (ActivityCompat.checkSelfPermission(
                    ime,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            speechService = MySpeechService(recognizer, recognizer.sampleRate)
            speechService!!.startListening(ime)
        } catch (e: IOException) {
            viewManager.errorMessageLD.postValue(R.string.mic_error_mic_in_use)
            viewManager.stateLD.postValue(ViewManager.Companion.STATE_ERROR)
        }
        isRunning = true
    }

    private var pausedState = false

    init {
        sourceProviders.add(VoskLocalProvider(ime))
        if (ModelPreferences.VOSK_SERVER_ENABLED) {
            sourceProviders.add(VoskServerProvider())
        }
        for (provider in sourceProviders) {
            provider.loadSources(recognizerSources)
        }
        if (recognizerSources.size == 0) {
            viewManager.errorMessageLD.postValue(R.string.mic_error_no_recognizers)
            viewManager.stateLD.postValue(ViewManager.Companion.STATE_ERROR)
        } else {
            currentRecognizerSourceIndex = 0
            initializeRecognizer()
        }
    }

    fun pause(checked: Boolean) {
        if (speechService != null) {
            speechService!!.setPause(checked)
            pausedState = checked
            if (checked) {
                viewManager.stateLD.postValue(ViewManager.Companion.STATE_PAUSED)
            } else {
                viewManager.stateLD.postValue(ViewManager.Companion.STATE_LISTENING)
            }
        } else {
            pausedState = false
        }
    }

    val isPaused: Boolean
        get() = pausedState && speechService != null

    fun stop() {
        if (speechService != null) {
            speechService!!.stop()
            speechService!!.shutdown()
        }
        speechService = null
        isRunning = false
        stopRecognizerSource()
    }

    fun onDestroy() {
        stop()
    }

    fun reloadModels() {
        val newModels: MutableList<RecognizerSource> = ArrayList()
        for (provider in sourceProviders) {
            provider.loadSources(newModels)
        }
        val currentModel = recognizerSources[currentRecognizerSourceIndex]
        recognizerSources = newModels
        currentRecognizerSourceIndex = newModels.indexOf(currentModel)
        if (currentRecognizerSourceIndex == -1) {
            currentRecognizerSourceIndex = 0
        }
    }
}