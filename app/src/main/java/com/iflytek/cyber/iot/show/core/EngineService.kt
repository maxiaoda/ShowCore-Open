package com.iflytek.cyber.iot.show.core

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iflytek.cyber.evs.sdk.EvsError
import com.iflytek.cyber.evs.sdk.EvsService
import com.iflytek.cyber.evs.sdk.RequestCallback
import com.iflytek.cyber.evs.sdk.agent.*
import com.iflytek.cyber.evs.sdk.auth.AuthDelegate
import com.iflytek.cyber.evs.sdk.focus.*
import com.iflytek.cyber.evs.sdk.socket.Result
import com.iflytek.cyber.iot.show.core.EvsLauncherActivity.Companion.ACTION_WAKE_UP
import com.iflytek.cyber.iot.show.core.fragment.MainFragment2
import com.iflytek.cyber.iot.show.core.impl.audioplayer.EvsAudioPlayer
import com.iflytek.cyber.iot.show.core.impl.launcher.EvsLauncher
import com.iflytek.cyber.iot.show.core.impl.playback.PlaybackControllerImpl
import com.iflytek.cyber.iot.show.core.impl.prompt.PromptManager
import com.iflytek.cyber.iot.show.core.impl.recognizer.EvsRecognizer
import com.iflytek.cyber.iot.show.core.impl.screen.EvsScreen
import com.iflytek.cyber.iot.show.core.impl.speaker.EvsSpeaker
import com.iflytek.cyber.iot.show.core.impl.system.EvsSystem
import com.iflytek.cyber.iot.show.core.impl.template.EvsTemplate
import com.iflytek.cyber.iot.show.core.impl.videoplayer.EvsVideoPlayer
import com.iflytek.cyber.iot.show.core.record.EvsIvwHandler
import com.iflytek.cyber.iot.show.core.record.GlobalRecorder
import com.iflytek.cyber.iot.show.core.utils.ConfigUtils
import com.iflytek.cyber.iot.show.core.utils.ConnectivityUtils
import com.iflytek.cyber.iot.show.core.utils.RequestIdMap
import org.greenrobot.eventbus.EventBus
import java.net.UnknownHostException

class EngineService : EvsService() {
    private val binder = EngineServiceBinder()

    companion object {
        private const val TAG = "EngineService"

        private const val ACTION_PREFIX = "com.iflytek.cyber.iot.show.core.action"

        const val ACTION_EVS_CONNECTED = "$ACTION_PREFIX.EVS_CONNECTED"
        const val ACTION_EVS_CONNECT_FAILED = "$ACTION_PREFIX.EVS_CONNECT_FAILED"
        const val ACTION_EVS_DISCONNECTED = "$ACTION_PREFIX.EVS_DISCONNECTED"
        const val ACTION_STOP_CAPTURE = "$ACTION_PREFIX.STOP_CAPTURE"
        const val ACTION_REQUEST_CANCEL = "$ACTION_PREFIX.REQUEST_CANCEL"
        const val ACTION_REQUEST_STOP_AUDIO_PLAYER = "$ACTION_PREFIX.REQUEST_STOP_AUDIO_PLAYER"
        const val ACTION_REQUEST_STOP_ALARM = "$ACTION_PREFIX.REQUEST_STOP_ALARM"
        const val ACTION_SET_WAKE_UP_ENABLED = "$ACTION_PREFIX.SET_WAKE_UP_ENABLED"
        const val ACTION_SEND_TEMPLATE_ELEMENT_SELECTED =
            "$ACTION_PREFIX.SEND_TEMPLATE_ELEMENT_SELECTED"
        const val ACTION_SEND_TEXT_IN = "$ACTION_PREFIX.SEND_TEXT_IN"
        const val ACTION_SEND_AUDIO_IN = "$ACTION_PREFIX.SEND_AUDIO_IN"
        const val ACTION_AUTH_REVOKED = "$ACTION_PREFIX.AUTH_REVOKED"
        const val ACTION_CLEAR_TEMPLATE_FOCUS = "$ACTION_PREFIX.CLEAR_TEMPLATE_FOCUS"

        const val ACTION_AUDIO_PLAYER_STARTED = "$ACTION_PREFIX.AUDIO_PLAYER_STARTED"
        const val ACTION_AUDIO_PLAYER_RESUMED = "$ACTION_PREFIX.AUDIO_PLAYER_RESUMED"
        const val ACTION_AUDIO_PLAYER_PAUSED = "$ACTION_PREFIX.AUDIO_PLAYER_PAUSED"
        const val ACTION_AUDIO_PLAYER_STOPPED = "$ACTION_PREFIX.AUDIO_PLAYER_STOPPED"
        const val ACTION_AUDIO_PLAYER_COMPLETED = "$ACTION_PREFIX.AUDIO_PLAYER_COMPLETED"
        const val ACTION_AUDIO_PLAYER_POSITION_UPDATED =
            "$ACTION_PREFIX.AUDIO_PLAYER_POSITION_UPDATED"
        const val ACTION_AUDIO_PLAYER_ERROR = "$ACTION_PREFIX.AUDIO_PLAYER_ERROR"
        const val ACTION_ALARM_STATE_CHANGED = "$ACTION_PREFIX.ALARM_STATE_CHANGED"
        const val ACTION_SEND_REQUEST_FAILED = "$ACTION_PREFIX.SEND_REQUEST_FAILED"

        const val EXTRA_CODE = "code"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_FROM_REMOTE = "from_remote"

        const val EXTRA_PLAYER_TYPE = "player_type"
        const val EXTRA_RESOURCE_ID = "resource_id"
        const val EXTRA_ERROR_CODE = "error_code"
        const val EXTRA_POSITION = "position"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_STATE = "alarm_state"
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_ELEMENT_ID = "element_id"
        const val EXTRA_QUERY = "query"
        const val EXTRA_WAKE_UP_JSON = "wake_up_json"
        const val EXTRA_RESULT = "result"
    }

    open inner class EngineServiceBinder : Binder() {
        fun getService(): EngineService {
            return this@EngineService
        }
    }

    private var transmissionListener: TransmissionListener? = null
    private var recognizerCallback: Recognizer.RecognizerCallback? = null
    private var templateRenderCallback: EvsTemplate.RenderCallback? = null
    private var preventWakeUp = false
    private lateinit var mIvwHandler: EvsIvwHandler

    private val handler = Handler()

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                val stopPlayers = Intent(baseContext, EngineService::class.java)
                stopPlayers.action = ACTION_REQUEST_STOP_AUDIO_PLAYER
                stopPlayers.putExtra(EXTRA_PLAYER_TYPE, AudioPlayer.TYPE_PLAYBACK)
                startService(stopPlayers)
            }
        }
    private var audioFocusRequest: Any? = null

    private val evsSystem = EvsSystem.get()
    private val internalRecordCallback = object : Recognizer.RecognizerCallback {
        override fun onBackgroundRecognizeStateChanged(isBackgroundRecognize: Boolean) {
            try {
                recognizerCallback?.onBackgroundRecognizeStateChanged(isBackgroundRecognize)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val intent = Intent(baseContext, FloatingService::class.java)
            intent.action = FloatingService.ACTION_SET_BACKGROUND_RECOGNIZE
            intent.putExtra(FloatingService.EXTRA_ENABLED, isBackgroundRecognize)
            startService(intent)
        }

        override fun onRecognizeStarted(isExpectReply: Boolean) {
            try {
                recognizerCallback?.onRecognizeStarted(isExpectReply)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (!isExpectReply)
                overlayVisualFocusChannel.requestActive()

            val intent = Intent(baseContext, FloatingService::class.java)
            intent.action = FloatingService.ACTION_SHOW_RECOGNIZE
            intent.putExtra(FloatingService.EXTRA_EXPECT_REPLY, isExpectReply)
            startService(intent)
        }

        override fun onRecognizeStopped() {
            try {
                recognizerCallback?.onRecognizeStopped()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            overlayVisualFocusChannel.requestAbandon()

            handler.postDelayed({
                if (getRecognizer().isRecording())
                    return@postDelayed
                val intent = Intent(baseContext, FloatingService::class.java)
                intent.action = FloatingService.ACTION_DISMISS_RECOGNIZE
                startService(intent)
            }, 1000)
        }

        override fun onIntermediateText(text: String) {
            Log.d(TAG, "onIntermediateText($text)")
            try {
                recognizerCallback?.onIntermediateText(text)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val intent = Intent(baseContext, FloatingService::class.java)
            intent.action = FloatingService.ACTION_INTERMEDIATE_TEXT
            intent.putExtra(FloatingService.EXTRA_TEXT, text)
            startService(intent)
        }
    }
    private val mIvwListener = object : EvsIvwHandler.IvwHandlerListener {
        override fun onWakeUp(oriMsg: String) {
            if (isEvsConnected && !preventWakeUp) {
                EventBus.getDefault().post(ACTION_WAKE_UP)

                PromptManager.playWakeSound()

                try {
                    val json = JsonParser().parse(oriMsg).asJsonObject
                    val rlt = json.getAsJsonArray("rlt")
                    val result = rlt.get(0).asJsonObject

                    val wakeUpJson = JsonObject()

                    wakeUpJson.addProperty("score", result.get("nkeywordscore").asInt)
                    wakeUpJson.addProperty("word", result.get("keyword").asString)

                    sendAudioIn(wakeUpJson.toString())
                } catch (e: Exception) {
                    e.printStackTrace()

                    sendAudioIn()
                }
            } else if (!isEvsConnected) {
                if (AuthDelegate.getAuthResponseFromPref(baseContext) == null) {
                    val disconnectNotification =
                        Intent(baseContext, FloatingService::class.java)
                    disconnectNotification.action =
                        FloatingService.ACTION_SHOW_NOTIFICATION
                    disconnectNotification.putExtra(
                        FloatingService.EXTRA_MESSAGE,
                        getString(R.string.message_evs_auth_expired)
                    )
                    disconnectNotification.putExtra(
                        FloatingService.EXTRA_TAG,
                        "auth_error"
                    )
                    disconnectNotification.putExtra(
                        FloatingService.EXTRA_ICON_RES,
                        R.drawable.ic_default_error_white_40dp
                    )
                    disconnectNotification.putExtra(
                        FloatingService.EXTRA_POSITIVE_BUTTON_TEXT,
                        getString(R.string.re_auth)
                    )
                    disconnectNotification.putExtra(
                        FloatingService.EXTRA_POSITIVE_BUTTON_ACTION,
                        MainFragment2.ACTION_OPEN_AUTH
                    )
                    disconnectNotification.putExtra(
                        FloatingService.EXTRA_KEEPING, true
                    )
                    baseContext.startService(disconnectNotification)
                } else {
                    ConnectivityUtils.checkNetworkAvailable({
                        // 网络可用但 EVS 断连
                        val disconnectNotification =
                            Intent(baseContext, FloatingService::class.java)
                        disconnectNotification.action =
                            FloatingService.ACTION_SHOW_NOTIFICATION
                        disconnectNotification.putExtra(
                            FloatingService.EXTRA_MESSAGE,
                            getString(R.string.message_evs_disconnected)
                        )
                        disconnectNotification.putExtra(
                            FloatingService.EXTRA_TAG,
                            "network_error"
                        )
                        disconnectNotification.putExtra(
                            FloatingService.EXTRA_ICON_RES,
                            R.drawable.ic_default_error_white_40dp
                        )
                        disconnectNotification.putExtra(
                            FloatingService.EXTRA_POSITIVE_BUTTON_TEXT,
                            getString(R.string.i_got_it)
                        )
                        disconnectNotification.putExtra(
                            FloatingService.EXTRA_KEEPING, true
                        )
                        startService(disconnectNotification)
                    }, { _, _ ->
                        // 网络不可用
                        PromptManager.play(PromptManager.NETWORK_LOST)

                        val networkErrorNotification =
                            Intent(baseContext, FloatingService::class.java)
                        networkErrorNotification.action =
                            FloatingService.ACTION_SHOW_NOTIFICATION
                        networkErrorNotification.putExtra(
                            FloatingService.EXTRA_MESSAGE, "网络连接异常，请重新设置"
                        )
                        networkErrorNotification.putExtra(
                            FloatingService.EXTRA_TAG, "network_error"
                        )
                        networkErrorNotification.putExtra(
                            FloatingService.EXTRA_POSITIVE_BUTTON_TEXT, "设置网络"
                        )
                        networkErrorNotification.putExtra(
                            FloatingService.EXTRA_POSITIVE_BUTTON_ACTION,
                            MainFragment2.ACTION_OPEN_WIFI
                        )
                        networkErrorNotification.putExtra(
                            FloatingService.EXTRA_ICON_RES,
                            R.drawable.ic_wifi_error_white_40dp
                        )
                        startService(networkErrorNotification)
                    })
                }
            }
        }
    }
    private val recordObserver = object : GlobalRecorder.Observer {
        override fun onAudioData(array: ByteArray, offset: Int, length: Int) {
            if (length > 0)
                mIvwHandler.write(array, length)
        }
    }
    private val innerTemplateCallback = object : EvsTemplate.RenderCallback {
        override fun renderTemplate(payload: String) {
            EvsSpeaker.get(baseContext).isVisualFocusGain = true

            EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)

            templateRenderCallback?.renderTemplate(payload)
        }

        override fun notifyPlayerInfoUpdated(resourceId: String, payload: String) {
            templateRenderCallback?.notifyPlayerInfoUpdated(resourceId, payload)
        }

        override fun renderPlayerInfo(payload: String) {
            templateRenderCallback?.renderPlayerInfo(payload)
        }

        override fun exitCustomTemplate() {
            EvsSpeaker.get(baseContext).isVisualFocusGain = true

            EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)

            templateRenderCallback?.exitCustomTemplate()
        }

        override fun exitPlayerInfo() {
            templateRenderCallback?.exitPlayerInfo()
        }

        override fun exitStaticTemplate(type: String?) {
            EvsSpeaker.get(baseContext).isVisualFocusGain = false

            EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)

            templateRenderCallback?.exitStaticTemplate(type)
        }

        override fun renderVideoPlayerInfo(payload: String) {
            templateRenderCallback?.renderVideoPlayerInfo(payload)
        }
    }
    private val audioPlayerListener = object : AudioPlayer.MediaStateChangedListener {
        override fun onStarted(player: AudioPlayer, type: String, resourceId: String) {
            val intent = Intent(ACTION_AUDIO_PLAYER_STARTED)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            sendBroadcast(intent)

            if (type == AudioPlayer.TYPE_TTS) {
                EvsSpeaker.get(baseContext).isAudioFocusGain = true

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            } else if (type == AudioPlayer.TYPE_PLAYBACK) {
                requestAudioFocus()
            }
        }

        override fun onResumed(player: AudioPlayer, type: String, resourceId: String) {
            val intent = Intent(ACTION_AUDIO_PLAYER_RESUMED)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            sendBroadcast(intent)

            if (type == AudioPlayer.TYPE_TTS) {
                EvsSpeaker.get(baseContext).isAudioFocusGain = true

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            } else if (type == AudioPlayer.TYPE_PLAYBACK) {
                requestAudioFocus()
            }
        }

        override fun onPaused(player: AudioPlayer, type: String, resourceId: String) {
            val intent = Intent(ACTION_AUDIO_PLAYER_PAUSED)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            sendBroadcast(intent)

            if (type == AudioPlayer.TYPE_TTS) {
                EvsSpeaker.get(baseContext).isAudioFocusGain = false

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            }
        }

        override fun onStopped(player: AudioPlayer, type: String, resourceId: String) {
            val intent = Intent(ACTION_AUDIO_PLAYER_STOPPED)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            sendBroadcast(intent)

            if (type == AudioPlayer.TYPE_TTS) {
                EvsSpeaker.get(baseContext).isAudioFocusGain = false

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            }
        }

        override fun onCompleted(player: AudioPlayer, type: String, resourceId: String) {
            val intent = Intent(ACTION_AUDIO_PLAYER_COMPLETED)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            sendBroadcast(intent)

            if (type == AudioPlayer.TYPE_TTS) {
                EvsSpeaker.get(baseContext).isAudioFocusGain = false

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            }
        }

        override fun onPositionUpdated(
            player: AudioPlayer,
            type: String,
            resourceId: String,
            position: Long
        ) {
            val intent = Intent(ACTION_AUDIO_PLAYER_POSITION_UPDATED)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            intent.putExtra(EXTRA_POSITION, position)
            sendBroadcast(intent)
        }

        override fun onError(
            player: AudioPlayer,
            type: String,
            resourceId: String,
            errorCode: String
        ) {
            val intent = Intent(ACTION_AUDIO_PLAYER_ERROR)
            intent.putExtra(EXTRA_RESOURCE_ID, resourceId)
            intent.putExtra(EXTRA_PLAYER_TYPE, type)
            intent.putExtra(EXTRA_ERROR_CODE, errorCode)
            sendBroadcast(intent)
        }
    }
    private val alarmStateChangedListener = object : Alarm.AlarmStateChangedListener {
        override fun onAlarmStateChanged(alarmId: String, state: Alarm.AlarmState) {
            val intent = Intent(ACTION_ALARM_STATE_CHANGED)
            intent.putExtra(EXTRA_ALARM_ID, alarmId)
            intent.putExtra(EXTRA_ALARM_STATE, state.toString())
            sendBroadcast(intent)
        }
    }
    private val volumeChangedListener = object : EvsSpeaker.OnVolumeChangedListener {
        override fun onVolumeChanged(volume: Int, fromRemote: Boolean) {
            if (fromRemote)
                PromptManager.play(PromptManager.VOLUME)
        }
    }
    private val launcherVisualFocusChannel = object : VisualFocusChannel() {
        override fun onFocusChanged(focusStatus: FocusStatus) {
            // don't need do any thing
        }

        override fun getChannelName(): String {
            return VisualFocusManager.CHANNEL_APP
        }

        override fun getType(): String {
            return "Launcher"
        }
    }
    private val overlayVisualFocusChannel = object : VisualFocusChannel() {
        override fun onFocusChanged(focusStatus: FocusStatus) {
            if (focusStatus == FocusStatus.Foreground) {
                EvsSpeaker.get(baseContext).isVisualFocusGain = true

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            } else {
                EvsSpeaker.get(baseContext).isVisualFocusGain = false

                EvsSpeaker.get(baseContext).refreshNativeAudioFocus(baseContext)
            }
        }

        override fun getChannelName(): String {
            return VisualFocusManager.CHANNEL_OVERLAY
        }

        override fun getType(): String {
            return "Recognize"
        }

    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    fun setTransmissionListener(listener: TransmissionListener?) {
        transmissionListener = listener
    }

    fun setRecognizerCallback(recognizerCallback: Recognizer.RecognizerCallback?) {
        this.recognizerCallback = recognizerCallback
    }

    fun setTemplateRenderCallback(templateRenderCallback: EvsTemplate.RenderCallback?) {
        this.templateRenderCallback = templateRenderCallback
    }

    override fun onCreate() {
        EvsSpeaker.get(this)

        super.onCreate()

        mIvwHandler = EvsIvwHandler(this, mIvwListener)
        GlobalRecorder.registerObserver(recordObserver)
        getRecognizer().setRecognizerCallback(internalRecordCallback)
        getAudioPlayer().addListener(audioPlayerListener)
        getAlarm()?.addListener(alarmStateChangedListener)

        PromptManager.init(this)
        PromptManager.setupAudioFocusManager(AudioFocusManager)
        (getVideoPlayer() as? EvsVideoPlayer)?.setManager(AudioFocusManager)

        launcherVisualFocusChannel.setupManager(VisualFocusManager)
        overlayVisualFocusChannel.setupManager(VisualFocusManager)

        EvsSpeaker.get(this).addOnVolumeChangedListener(volumeChangedListener)

        EvsLauncher.get().init(this)

        // 根据应用版本区分是否上报事件
        ConfigUtils.getInt(ConfigUtils.KEY_VERSION_CODE, -1).let {
            if (BuildConfig.VERSION_CODE > it) {
                getSystem().sendUpdateSoftwareFinished(BuildConfig.VERSION_NAME, null)
                ConfigUtils.putInt(ConfigUtils.KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
            }
        }

        evsSystem.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAPTURE -> {
                getRecognizer().stopCapture()
            }
            ACTION_REQUEST_CANCEL -> {
                getRecognizer().requestCancel()
            }
            ACTION_SET_WAKE_UP_ENABLED -> {
                val wakeUpEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                preventWakeUp = wakeUpEnabled == false

                val setWakeUpEnabled = Intent(this, FloatingService::class.java)
                setWakeUpEnabled.action = FloatingService.ACTION_SET_WAKE_UP_ENABLED
                setWakeUpEnabled.putExtra(FloatingService.EXTRA_ENABLED, wakeUpEnabled)
                startService(setWakeUpEnabled)
            }
            ACTION_REQUEST_STOP_AUDIO_PLAYER -> {
                val type = intent.getStringExtra(EXTRA_PLAYER_TYPE)
                if (type.isNullOrEmpty()) {
                    getAudioPlayer().stop(AudioPlayer.TYPE_TTS)
                    getAudioPlayer().stop(AudioPlayer.TYPE_RING)
                    getAudioPlayer().stop(AudioPlayer.TYPE_PLAYBACK)
                } else {
                    getAudioPlayer().stop(type)
                }
            }
            ACTION_REQUEST_STOP_ALARM -> {
                getAlarm()?.stop() ?: run {
                    getAudioPlayer().stop(AudioPlayer.TYPE_RING)
                }
            }
            ACTION_SEND_TEMPLATE_ELEMENT_SELECTED -> {
                getTemplate()?.let { templateAgent ->
                    val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
                    val elementId = intent.getStringExtra(EXTRA_ELEMENT_ID)
                    templateAgent.sendElementSelected(templateId, elementId)
                }
            }
            ACTION_SEND_TEXT_IN -> {
                sendTextIn(intent.getStringExtra(EXTRA_QUERY))
            }
            ACTION_SEND_AUDIO_IN -> {
                val json = intent.getStringExtra(EXTRA_WAKE_UP_JSON)
                mIvwListener.onWakeUp(json ?: "")
            }
            ACTION_AUTH_REVOKED -> {
                val openAuth = Intent(this, EvsLauncherActivity::class.java)
                openAuth.action = EvsLauncherActivity.ACTION_OPEN_AUTH
                openAuth.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(openAuth)

                getVideoPlayer()?.stop()
                getAudioPlayer().apply {
                    stop(AudioPlayer.TYPE_TTS)
                    stop(AudioPlayer.TYPE_PLAYBACK)
                    stop(AudioPlayer.TYPE_RING)
                }
                getAlarm()?.stop()
            }
            ACTION_CLEAR_TEMPLATE_FOCUS -> {
                clearCurrentTemplateFocus()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()

        GlobalRecorder.stopRecording()
        GlobalRecorder.unregisterObserver(recordObserver)
        mIvwHandler.release()
        getRecognizer().removeRecognizerCallback()
        getAudioPlayer().removeListener(audioPlayerListener)
        getAlarm()?.removeListener(alarmStateChangedListener)

        PromptManager.destroy()

        evsSystem.destroy(this)

        EvsSpeaker.get(this).removeOnVolumeChangedListener(volumeChangedListener)
    }

    override fun onEvsConnected() {
        super.onEvsConnected()

        sendBroadcast(Intent(ACTION_EVS_CONNECTED))
    }

    override fun onEvsDisconnected(code: Int, message: String?, fromRemote: Boolean) {
        super.onEvsDisconnected(code, message, fromRemote)

        val intent = Intent(ACTION_EVS_DISCONNECTED)
        intent.putExtra(EXTRA_CODE, code)
        intent.putExtra(EXTRA_MESSAGE, message)
        intent.putExtra(EXTRA_FROM_REMOTE, fromRemote)
        sendBroadcast(intent)
    }

    override fun onConnectFailed(t: Throwable?) {
        Log.w(TAG, "onConnectFailed", t)
        val intent = Intent(ACTION_EVS_CONNECT_FAILED)
        sendBroadcast(intent)
        when (t) {
            is UnknownHostException -> {
                // 无网络
            }
            is EvsError.AuthorizationExpiredException -> {
                // Token 过期
                AuthDelegate.removeAuthResponseFromPref(this)

                val disconnectNotification =
                    Intent(baseContext, FloatingService::class.java)
                disconnectNotification.action = FloatingService.ACTION_SHOW_NOTIFICATION
                disconnectNotification.putExtra(
                    FloatingService.EXTRA_MESSAGE, getString(R.string.message_evs_auth_expired)
                )
                disconnectNotification.putExtra(FloatingService.EXTRA_TAG, "auth_error")
                disconnectNotification.putExtra(
                    FloatingService.EXTRA_ICON_RES, R.drawable.ic_default_error_white_40dp
                )
                disconnectNotification.putExtra(
                    FloatingService.EXTRA_POSITIVE_BUTTON_TEXT, getString(R.string.re_auth)
                )
                disconnectNotification.putExtra(
                    FloatingService.EXTRA_POSITIVE_BUTTON_ACTION, MainFragment2.ACTION_OPEN_AUTH
                )
                disconnectNotification.putExtra(
                    FloatingService.EXTRA_KEEPING, true
                )
                startService(disconnectNotification)
            }
        }
    }

    override fun onSendFailed(code: Int, reason: String?) {

    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioManager.requestAudioFocus(request)

            audioFocusRequest = request
        } else {
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest as? AudioFocusRequest ?: return
            audioManager.abandonAudioFocusRequest(request)
        } else {
            audioManager.abandonAudioFocus(
                audioFocusListener
            )
        }
    }

    fun connectEvs(deviceId: String) {
        connect("wss://ivs.iflyos.cn/embedded/v1", deviceId)
    }

    fun requestLauncherVisualFocus() {
        launcherVisualFocusChannel.requestActive()
    }

    fun sendAudioIn(wakeUpJson: String? = null, replyKey: String? = null) {
        getRecognizer().sendAudioIn(wakeUpJson, replyKey, object : RequestCallback {
            override fun onResult(result: Result) {
                if (!result.isSuccessful) {
                    val intent = Intent(ACTION_SEND_REQUEST_FAILED)
                    intent.putExtra(EXTRA_RESULT, result)
                    sendBroadcast(intent)
                }
            }
        })
    }

    fun sendTextIn(query: String, replyKey: String? = null) {
        Log.d(TAG, "sendTextIn")
        getRecognizer().sendTextIn(query, replyKey, object : RequestCallback {
            override fun onResult(result: Result) {
                Log.d(TAG, "sendTextIn result: $result")
                if (!result.isSuccessful) {
                    val intent = Intent(ACTION_SEND_REQUEST_FAILED)
                    intent.putExtra(EXTRA_RESULT, result)
                    sendBroadcast(intent)
                }
            }
        })
    }

    override fun overrideSpeaker(): Speaker {
        return EvsSpeaker.get(this)
    }

    override fun overrideRecognizer(): Recognizer {
        return EvsRecognizer(this)
    }

    override fun overrideTemplate(): Template? {
        val template = EvsTemplate.get()
        template.renderCallback = innerTemplateCallback
        return template
    }

    override fun overrideScreen(): Screen? {
        return EvsScreen.get(this)
    }

    override fun onResponsesRaw(json: String) {
        super.onResponsesRaw(json)

        Thread {
            val jsonObject = JsonParser().parse(json).asJsonObject
            val meta = jsonObject.getAsJsonObject("iflyos_meta")
            val requestId = meta.get("request_id")?.asString ?: return@Thread
            val responses = jsonObject.getAsJsonArray("iflyos_responses")
            for (i in 0 until responses.size()) {
                val item = responses[i].asJsonObject
                val header = item.getAsJsonObject("header")
                val headerName = header.get("name")?.asString
                if (headerName?.startsWith("template.") == true) {
                    val payload = item.getAsJsonObject("payload")

                    // 如果是 playing_template，不存在 template_id 则直接忽略

                    val templateId = payload.get("template_id")?.asString ?: return@Thread

                    RequestIdMap.putRequestTemplate(requestId, templateId)
                } else if (headerName?.startsWith("audio_player") == true) {
                    val payload = item.getAsJsonObject("payload")
                    val type = payload.get("type")?.asString
                    if (type == AudioPlayer.TYPE_TTS) {
                        val resourceId = payload.get("resource_id")?.asString ?: return@Thread

                        RequestIdMap.putRequestTts(requestId, resourceId)
                    }
                }
            }
        }.start()

        transmissionListener?.onResponsesRaw(json)
    }

    override fun onRequestRaw(obj: Any) {
        super.onRequestRaw(obj)

        transmissionListener?.onRequestRaw(obj)
    }

    override fun overrideAudioPlayer(): AudioPlayer {
        return EvsAudioPlayer(this)
    }

    override fun overrideSystem(): System {
        return evsSystem
    }

    override fun overrideLauncher(): Launcher? {
        return EvsLauncher.get()
    }

    override fun overridePlaybackController(): PlaybackController? {
        return PlaybackControllerImpl()
    }

    override fun overrideVideoPlayer(): VideoPlayer? {
        return EvsVideoPlayer(this)
    }

    override fun getExternalAudioFocusChannels(): List<AudioFocusChannel> {
        val videoChannel = (getVideoPlayer() as? EvsVideoPlayer)?.videoChannel
        return if (videoChannel != null) {
            listOf(PromptManager.promptAudioChannel, videoChannel)
        } else {
            listOf(PromptManager.promptAudioChannel)
        }
    }

    override fun getExternalVisualFocusChannels(): List<VisualFocusChannel> {
        return listOf(
            launcherVisualFocusChannel,
            overlayVisualFocusChannel
        )
    }

    interface TransmissionListener {
        fun onResponsesRaw(json: String)
        fun onRequestRaw(obj: Any)
    }
}