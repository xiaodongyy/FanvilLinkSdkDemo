package com.fanvil.link.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import com.fanvil.link.sdk.utils.FvlLogger
import com.fanvil.link.sdk.bridge.SipMqttBridge
import com.fanvil.link.sdk.call.CallService
import com.fanvil.link.sdk.call.CallSession
import com.fanvil.link.sdk.call.CallState
import com.fanvil.link.sdk.door.DoorService
import com.fanvil.link.sdk.listener.FvSdkListener
import com.fanvil.link.sdk.mqtt.MqttClientHolder
import com.fanvil.link.sdk.mqtt.buildMqttUsername
import com.fanvil.link.sdk.mqtt.defaultSubscribeTopics
import com.fanvil.link.sdk.rtc.FvRtcVideoView
import com.fanvil.link.sdk.rtc.RinoRtcEngine
import com.fanvil.link.sdk.rtc.RtcViewRegistry
import com.fanvil.link.sdk.sip.SipCore
import com.fanvil.link.sdk.sip.SipRegistrationState
import com.fanvil.link.sdk.utils.RinoAudioUtils
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fv CloudTalk 原生 SDK 入口（开门 / 呼叫 / RTC）。
 * 原生工程与 Expo Module 共用同一套实现。
 */
object FvCloudTalkSDK {
    private val log = FvlLogger.getLogger("FvCloudTalkSDK")

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var config: FvSdkConfig? = null

    private var mqtt: MqttClientHolder? = null
    private var sip: SipCore? = null
    private var rtc: RinoRtcEngine? = null

    @Volatile
    private var rtcVideoView: FvRtcVideoView? = null
    private var bridge: SipMqttBridge? = null
    private var activeCall: CallSession? = null

    @Volatile
    private var mediaJoined = false

    @Volatile
    private var monitorMode = false

    @Volatile
    private var lastCallState: CallState? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitorRemainSeconds = 0
    private val monitorTick = object : Runnable {
        override fun run() {
            val remain = monitorRemainSeconds
            listeners.forEach { it.onMonitorCountdown(remain) }
            if (remain <= 0) {
                stopMonitorTimer()
                endCall()
                return
            }
            monitorRemainSeconds = remain - 1
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val listeners = CopyOnWriteArrayList<FvSdkListener>()

    fun addListener(listener: FvSdkListener) {
        listeners.add(listener)
        log.d("addListener size=${listeners.size}")
    }

    fun removeListener(listener: FvSdkListener) {
        listeners.remove(listener)
        log.d("removeListener size=${listeners.size}")
    }

    fun initialize(context: Context, config: FvSdkConfig) {
        FvlLogger.applyBuildType(
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        log.i(
            "initialize userId=${config.userId} agoraId=${config.agoraId} " +
                    "mqttUrl=${config.mqttUrl.trim()} mqttUser=${config.mqttUserName.trim()} " +
                    "appId=${config.agoraAppId}",
        )
        log.d("initialize token=${config.accessToken}")
        val app = context.applicationContext
        appContext = app
        this.config = config

        val mqttHolder = mqtt ?: MqttClientHolder(
            onConnectionChanged = { status, code, message, reconnect ->
                log.i("mqtt connection status=$status code=$code reconnect=$reconnect msg=$message")
                listeners.forEach { it.onMqttConnectionChanged(status, code, message, reconnect) }
                bridge?.onMqttConnectionChanged(status == "connected")
            },
            onMessage = { topic, payload ->
                log.d("mqtt message topic=$topic payload=$payload")
                listeners.forEach { it.onMqttMessage(topic, payload) }
                bridge?.onMqttMessage(topic, payload)
            },
        ).also { mqtt = it }

        val sipCore = sip ?: SipCore(app) { event, payload ->
            dispatchSipEvent(event, payload)
        }.also { sip = it }

        val rtcEngine = rtc ?: RinoRtcEngine(app) { event, payload ->
            log.d("rtc event=$event")
            listeners.forEach { it.onRtcEvent(event, payload) }
        }.also { rtc = it }

        val topics = defaultSubscribeTopics(config.userId, config.agoraId)
        if (!mqttHolder.isConnected()) {
            log.i("mqtt connect clientId=${config.userId}")
            mqttHolder.connect(
                url = config.mqttUrl.trim(),
                clientId = config.userId,
                username = buildMqttUsername(config.mqttUserName.trim()),
                password = config.accessToken.trim(),
            )
        } else {
            log.i("mqtt already connected, skip connect")
        }
        log.i("mqtt subscribe topics=$topics")
        mqttHolder.subscribe(topics)

        val sipBridge = bridge ?: SipMqttBridge(mqttHolder, sipCore, rtcEngine).also { bridge = it }
        sipBridge.onRtcTokenReady = {
            if (lastCallState == CallState.IncomingEarlyMedia && !mediaJoined) {
                log.i("rtc token ready, joinMedia for IncomingEarlyMedia")
                try {
                    joinEarlyMedia()
                } catch (e: Exception) {
                    log.e("joinMedia after token ready failed", e)
                }
            }
        }
        sipBridge.onSipOutgoing = { topic, payload ->
            listeners.forEach { it.onSipOutgoing(topic, payload) }
        }
        sipBridge.start(
            agoraId = config.agoraId,
            userId = config.userId,
            agoraAppId = config.agoraAppId.trim(),
            displayName = config.displayName,
        )
        log.i("initialize done userId=${config.userId} agoraId=${config.agoraId}")
    }

    fun isReady(): Boolean {
        val ready = config != null && mqtt?.isConnected() == true
        log.t("isReady=$ready")
        return ready
    }

    fun publish(topic: String, payload: String) {
        val mqttHolder = mqtt ?: throw IllegalStateException("SDK not initialized")
        mqttHolder.publish(topic, payload)
    }

    fun reconnect() {
        val cfg = config ?: throw IllegalStateException("SDK not initialized")
        val mqttHolder = mqtt ?: throw IllegalStateException("SDK not initialized")
        if (mqttHolder.isConnected()) return
        mqttHolder.connect(
            url = cfg.mqttUrl.trim(),
            clientId = cfg.userId,
            username = buildMqttUsername(cfg.mqttUserName.trim()),
            password = cfg.accessToken.trim(),
        )
        mqttHolder.subscribe(defaultSubscribeTopics(cfg.userId, cfg.agoraId))
    }

    fun isCalling(): Boolean {
        val calling = sip?.isCalling() == true || activeCall != null
        log.t("isCalling=$calling")
        return calling
    }

    fun isMediaJoined(): Boolean {
        log.t("isMediaJoined=$mediaJoined")
        return mediaJoined
    }

    fun isMonitorMode(): Boolean {
        log.t("isMonitorMode=$monitorMode")
        return monitorMode
    }

    fun getActiveCall(): CallSession? {
        log.t("getActiveCall callId=${activeCall?.callId}")
        return activeCall
    }

    fun getRtcView(context: Context): FvRtcVideoView {
        log.d("getRtcView reuse=${rtcVideoView != null}")
        if (appContext == null) appContext = context.applicationContext
        val existing = rtcVideoView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return FvRtcVideoView(context).also { rtcVideoView = it }
    }

    /** 强制丢掉当前视频容器，下次 getRtcView 会新建。普通切页不必调。 */
    fun releaseRtcView() {
        log.i("releaseRtcView held=${rtcVideoView != null}")
        val view = rtcVideoView ?: return
        view.removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        rtcVideoView = null
        RtcViewRegistry.current = null
    }

    internal fun onRtcViewDetached(view: FvRtcVideoView) {
        if (rtcVideoView !== view) return
        if (view.isAttachedToWindow) return
        log.i("onRtcViewDetached")
        view.removeAllViews()
        rtcVideoView = null
        RtcViewRegistry.current = null
    }

    fun openDoor(mac: String, whichDoor: Int = 1, doorNoList: List<Int>? = null) {
        log.i("openDoor mac=$mac whichDoor=$whichDoor doorNoList=$doorNoList")
        if (!ensureInitialized()) return
        val cfg = config ?: return
        val mqttHolder = mqtt ?: return
        if (!mqttHolder.isConnected()) {
            log.w("openDoor skipped: MQTT is not connected")
            return
        }
        DoorService.openDoor(mqttHolder, cfg.userId, mac, whichDoor, doorNoList)
        log.i("openDoor published userId=${cfg.userId}")
    }

    fun startCall(
        sipUsername: String,
        isVideo: Boolean = true,
    ) {
        log.i("startCall sipUsername=$sipUsername isVideo=$isVideo")
        startOutgoingCall(sipUsername, isVideo = isVideo, isMonitor = false)
    }

    private fun startOutgoingCall(
        sipUsername: String,
        isVideo: Boolean,
        isMonitor: Boolean,
    ): Boolean {
        val type = when {
            isMonitor -> "monitor"
            isVideo -> "video"
            else -> "audio"
        }
        log.i("startCall sipUsername=$sipUsername isVideo=$isVideo isMonitor=$isMonitor")
        if (!ensureInitialized()) return false
        val sipCore = sip ?: return false
        if (sipCore.isCalling()) {
            log.w("startCall skipped: already calling")
            return false
        }
        bridge?.resetRtcSession()
        rtc?.clearCache()
        val session = CallService.startCall(sipCore, sipUsername, type = type)
        activeCall = session
        mediaJoined = false
        monitorMode = isMonitor
        log.i("startCall ok callId=${session.callId} monitorMode=$monitorMode")
        return true
    }

    private fun joinMedia(speakerOn: Boolean = true) {
        val micEnabled = !monitorMode
        log.i("joinMedia speakerOn=$speakerOn micEnabled=$micEnabled monitorMode=$monitorMode")
        joinCallMedia(rtcVideoView, speakerOn, micEnabled = micEnabled)
    }

    private fun joinEarlyMedia() {
        log.i("joinEarlyMedia")
        appContext?.let { RinoAudioUtils.setMicrophoneMute(it, true) }
        joinCallMedia(rtcVideoView, false, micEnabled = false)
        rtc?.setMuteAudio(true)
    }

    private fun joinCallMedia(
        videoContainer: ViewGroup? = null,
        speakerOn: Boolean = true,
        micEnabled: Boolean = true,
    ) {
        log.i(
            "joinCallMedia speakerOn=$speakerOn micEnabled=$micEnabled " +
                    "hasContainer=${videoContainer != null}",
        )
        val sipBridge = bridge
        if (!ensureInitialized() || sipBridge == null) {
            return
        }
        if (mediaJoined) {
            log.i("joinCallMedia skipped: already joined")
            return
        }
        mediaJoined = true
        try {
            sipBridge.joinOutgoingCallRtc(videoContainer, speakerOn, micEnabled = micEnabled)
        } catch (e: Exception) {
            mediaJoined = false
            log.e("joinCallMedia failed", e)
            return
        }
        log.i("joinCallMedia done")
    }

    fun startMonitor(
        sipUsername: String,
        timeoutSeconds: Int = 30,
    ) {
        log.i("startMonitor sipUsername=$sipUsername timeoutSeconds=$timeoutSeconds")
        if (!ensureInitialized()) return
        setMuted(true)
        if (!startOutgoingCall(sipUsername, isVideo = true, isMonitor = true)) return
        startMonitorTimer(timeoutSeconds)
    }

    private fun startMonitorTimer(timeoutSeconds: Int) {
        log.d("startMonitorTimer timeoutSeconds=$timeoutSeconds")
        stopMonitorTimer()
        if (timeoutSeconds <= 0) return
        monitorRemainSeconds = timeoutSeconds
        mainHandler.post(monitorTick)
    }

    private fun stopMonitorTimer() {
        log.t("stopMonitorTimer")
        mainHandler.removeCallbacks(monitorTick)
    }

    fun takeSnapshot(filePath: String? = null, saveToGallery: Boolean = false): Int {
        log.i("takeSnapshot filePath=$filePath saveToGallery=$saveToGallery")
        if (!ensureInitialized()) return -1
        val rtcEngine = rtc ?: return -1
        val code = rtcEngine.takeSnapshot(filePath, saveToGallery)
        log.i("takeSnapshot result=$code")
        return code
    }

    fun clearRtcCache() {
        log.i("clearRtcCache")
        try {
            rtc?.clearCache()
        } catch (e: Exception) {
            log.w("clearRtcCache failed", e)
        }
    }

    fun acceptCall() {
        log.i("acceptCall")
        if (!ensureInitialized()) return
        val sipCore = sip ?: return
        monitorMode = false
        mediaJoined = false
        CallService.accept(sipCore)
    }

    fun rejectCall() {
        log.i("rejectCall")
        endCall()
    }

    fun endCall() {
        log.i("endCall activeCallId=${activeCall?.callId} calling=${sip?.isCalling()}")
        val sipCore = sip ?: return
        CallService.hangup(sipCore, rtc)
        activeCall = null
        mediaJoined = false
        monitorMode = false
        lastCallState = null
        stopMonitorTimer()
        log.i("endCall done")
    }

    fun setMuted(muted: Boolean) {
        log.i("setMuted muted=$muted")
        appContext?.let { RinoAudioUtils.setMicrophoneMute(it, muted) }
    }

    fun setSpeakerOn(enabled: Boolean) {
        log.i("setSpeakerOn enabled=$enabled")
        val sipCore = sip ?: return
        CallService.setSpeakerOn(sipCore, rtc, enabled)
    }


    fun destroy() {
        log.i("destroy begin calling=${sip?.isCalling()} activeCall=${activeCall != null}")
        stopMonitorTimer()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            if (sip?.isCalling() == true || activeCall != null) {
                endCall()
            }
        } catch (e: Exception) {
            log.w("destroy endCall failed", e)
        }
        try {
            appContext?.let {
                RinoAudioUtils.setMicrophoneMute(it, false)
                RinoAudioUtils.setAudioManagerInNormalMode(it)
            }
        } catch (e: Exception) {
            log.w("destroy audio reset failed", e)
        }
        try {
            sip?.delete()
        } catch (e: Exception) {
            log.w("destroy delete failed", e)
        }
        try {
            bridge?.stop()
        } catch (e: Exception) {
            log.w("destroy bridge stop failed", e)
        }
        bridge = null
        try {
            mqtt?.disconnect()
        } catch (e: Exception) {
            log.w("destroy mqtt disconnect failed", e)
        }
        try {
            rtc?.destroy()
        } catch (e: Exception) {
            log.w("destroy rtc destroy failed", e)
        }
        try {
            sip?.destroy()
        } catch (e: Exception) {
            log.w("destroy sip destroy failed", e)
        }
        mqtt = null
        sip = null
        rtc = null
        rtcVideoView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        rtcVideoView = null
        activeCall = null
        mediaJoined = false
        monitorMode = false
        lastCallState = null
        config = null
        appContext = null
        listeners.clear()
        log.i("destroy done")
    }

    private fun ensureInitialized(): Boolean {
        if (config != null && sip != null) return true
        log.w("SDK not initialized")
        val ctx = appContext ?: return false
        mainHandler.post {
            Toast.makeText(ctx, "SDK not initialized", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun dispatchSipEvent(event: String, payload: Map<String, Any?>) {
        log.i("sip event=$event payload=$payload")
        when (event) {
            "onRegistration" -> {
                val state = payload["state"] as? SipRegistrationState ?: return
                listeners.forEach {
                    it.onSipRegistration(state, payload["message"] as? String ?: "")
                }
            }

            "onCallStateChanged" -> {
                val state = payload["state"] as? CallState ?: return
                val callId = payload["callId"] as? String
                val deviceId = payload["deviceId"] as? String
                val startedAt = (payload["startedAt"] as? Number)?.toLong()
                if (!callId.isNullOrEmpty() && !deviceId.isNullOrEmpty() && startedAt != null) {
                    activeCall = CallSession(callId, deviceId, startedAt)
                }
                val prevState = lastCallState
                lastCallState = state
                if (state == CallState.Incoming) {
                    monitorMode = false
                    mediaJoined = false
                    stopMonitorTimer()
                }
                // Connected 直接进；EarlyMedia 仅 token 已就绪时进，否则等 onRtcTokenReady 补进
                if (state == CallState.IncomingEarlyMedia && bridge?.isRtcReady() == true) {
                    try {
                        joinEarlyMedia()
                    } catch (e: Exception) {
                        log.e("auto joinEarlyMedia failed", e)
                    }
                }
                if (state == CallState.Connected) {
                    try {
                        joinMedia(false)
                    } catch (e: Exception) {
                        log.e("auto joinMedia failed", e)
                    }
                    if (prevState == CallState.IncomingEarlyMedia) {
                        rtc?.setMuteAudio(false)
                    }
                }
                if (state == CallState.End || state == CallState.Released || state == CallState.Error) {
                    try {
                        rtc?.leaveChannel()
                    } catch (e: Exception) {
                        log.w("leaveChannel on call end failed", e)
                    }
                    mediaJoined = false
                    monitorMode = false
                    lastCallState = null
                    stopMonitorTimer()
                }
                listeners.forEach {
                    it.onCallStateChanged(
                        state,
                        payload["remoteUsername"] as? String,
                        payload["remoteDisplayName"] as? String,
                        payload["remoteAddress"] as? String,
                    )
                }
                if (state == CallState.Released || state == CallState.Error) {
                    bridge?.resetRtcSession()
                    activeCall = null
                }
            }
        }
    }
}
