package com.iflytek.cyber.iot.show.core.skillapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.iflytek.cyber.evs.sdk.agent.AudioPlayer
import com.iflytek.cyber.iot.show.core.EngineService
import com.iflytek.cyber.iot.show.core.FloatingService
import com.iflytek.cyber.iot.show.core.R
import com.iflytek.cyber.iot.show.core.impl.audioplayer.EvsAudioPlayer
import com.iflytek.cyber.iot.show.core.impl.launcher.EvsLauncher
import com.iflytek.cyber.iot.show.core.utils.ConfigUtils

class SkillAppView(context: Context, private val service: FloatingService, url: String, timeout: Int) : FrameLayout(context) {

    companion object {
        private const val SESSION_TIMEOUT = 15 * 60 * 1000
    }

    private var webView: WebView? = null
    private var loadingView: LottieAnimationView? = null

    private var timer: CountDownTimer? = null

    private var shouldShowVoice = false
    private var isLoading = false

    init {
        val layout = LayoutInflater.from(context).inflate(R.layout.fragment_skill, this, false)
        this.addView(layout)

        webView = layout.findViewById(R.id.webview)
        loadingView = layout.findViewById(R.id.loading)
        loadingView?.alpha = 1f
        loadingView?.playAnimation()

        webView?.settings?.javaScriptEnabled = true
        webView?.settings?.domStorageEnabled = true
        webView?.settings?.allowFileAccess = true

        webView?.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url =
                    request?.url?.toString() ?: return super.shouldOverrideUrlLoading(view, request)
                return shouldOverrideUrlLoading(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrEmpty())
                    return super.shouldOverrideUrlLoading(view, url)
                return if (url == "close://now") {
                    service.closeSkillApp()
                    true
                } else {
                    super.shouldOverrideUrlLoading(view, url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false

                loadingView?.animate()?.alpha(0f)?.setDuration(150)?.start()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
            }
        }

        webView?.addJavascriptInterface(SkillInterface(), "androidApi")
        webView?.loadUrl(url)

        if (timeout > 0) {
            startCount(timeout * 1000)
        }

        val isMicrophoneEnabled =
            ConfigUtils.getBoolean(ConfigUtils.KEY_VOICE_WAKEUP_ENABLED, true)
        shouldShowVoice = isMicrophoneEnabled

        val intent = Intent(context, FloatingService::class.java).apply {
            action = FloatingService.ACTION_HIDE_VOICE_BUTTON
        }
        context.startService(intent)
    }

    private fun startCount(timeout: Int) {
        timer?.cancel()
        timer = object : CountDownTimer(timeout.toLong(), 1000) {
            override fun onFinish() {
                service.closeSkillApp()
            }

            override fun onTick(millisUntilFinished: Long) {
            }
        }
        timer?.start()
    }

    fun setSemanticData(semanticData: String) {
        //webView?.loadUrl("javascript:handleSemanticData('$semanticData')")
        startCount(SESSION_TIMEOUT)
        webView?.evaluateJavascript(
            "javascript:handleSemanticData($semanticData)"
        ) {
            Log.d("SkillAppView", "receive value: $it")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timer?.cancel()
        EvsLauncher.get().clearInternalAppId()
        EvsAudioPlayer.get(context).pause(AudioPlayer.TYPE_TTS)
        webView?.destroy()
        if (shouldShowVoice) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = FloatingService.ACTION_SHOW_VOICE_BUTTON
            }
            context.startService(intent)
        }
    }

    inner class SkillInterface {

        @JavascriptInterface
        fun audio_in() {
            val intent = Intent(context, EngineService::class.java)
            intent.action = EngineService.ACTION_SEND_AUDIO_IN
            context.startService(intent)
        }
    }
}