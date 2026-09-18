package com.fanvil.link.sdk.rtc

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.fanvil.link.sdk.utils.FvlLogger
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import com.smart.rinoiot.device_sdk.bean.device.AgoraRtcTokenVO
import com.smart.rinoiot.device_sdk.bean.device.AgoraRtmTokenVO
import com.smart.rinoiot.device_sdk.bean.device.AgoraUserTokenVO
import com.smart.rinoiot.panel_sdk.ipc.agora.RinoEventListener
import com.smart.rinoiot.panel_sdk.ipc.agora.RinoIPCEventEmitter
import com.smart.rinoiot.panel_sdk.rinoIPCSDK.RinoIPCSDK
import com.smart.rinoiot.panel_sdk.rinoIPCSDK.RinoRemotePlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 瀵归綈 AndroidDoorAccess library_rino / RinoManager 鐨勬牳蹇冭兘鍔涳紙鍘绘帀 EventBus / 涓氬姟渚濊禆锛夈€?
 */
class RinoRtcEngine(
  private val context: Context,
  private val emit: (event: RtcEvent, payload: Map<String, Any?>) -> Unit,
) : RinoEventListener {
  companion object {
    const val FV_DEVICE_REMOTE_ID = 100000004
    var dynamicAudioCodec: Int = 8
    private val xlogInitialized = AtomicBoolean(false)
  }

  private val log = FvlLogger.getLogger("RinoRtcEngine")

  private var eventEmitter: RinoIPCEventEmitter? = RinoIPCEventEmitter().also {
    it.addListener(this)
  }

  private var agoraUserTokenVO: AgoraUserTokenVO? = null
  private var remoteChannelName: String = ""
  private var remoteUid: Int = FV_DEVICE_REMOTE_ID
  private var agoraAppId: String = ""
  private var hasJoinChannelJob = false
  private var isMicEnabled = false
  private var isMuteAudio = false
  private var rinoRemotePlayer: RinoRemotePlayer? = null
  private var playerContainer: ViewGroup? = null
  private var lastSnapshotPath: String? = null
  private val mediaGeneration = AtomicLong(0)
  private var joinSpeakerOn = true
  private val mainHandler = Handler(Looper.getMainLooper())

  fun initIpc(appId: String) {
    agoraAppId = appId
    if (!RinoIPCSDK.hasInit) {
      ensureXLogInitialized()
      RinoIPCSDK.init(appId, eventEmitter, context)
      log.i("RinoIPCSDK init done")
    }
  }

  private fun ensureXLogInitialized() {
    if (!xlogInitialized.compareAndSet(false, true)) return
    try {
      XLog.init(LogLevel.ALL, AndroidPrinter())
      log.i("XLog initialized")
    } catch (e: Exception) {
      // 宿主已初始化时忽略
      log.d("XLog init skipped: ${e.message}")
    }
  }

  fun setToken(
    agoraAppId: String,
    userId: String,
    channelName: String,
    uid: Int,
    rtcToken: String,
    expireSecond: Int = 3600,
    rtmAccount: String? = null,
    rtmToken: String? = null,
    rtmExpire: Int = 3600,
  ) {
    this.agoraAppId = agoraAppId
    val token = AgoraUserTokenVO().apply {
      this.agoraAppId = agoraAppId
      this.userId = userId
      this.rtcToken = AgoraRtcTokenVO().apply {
        this.channelName = channelName
        // SDK 涓?uid 涓?String锛堝榻?RinoManager: rtcToken.uid.toInt()锛?
        this.uid = uid.toString()
        this.rtcToken = rtcToken
        this.expireSecond = expireSecond
      }
      if (!rtmToken.isNullOrEmpty()) {
        this.rtmToken = AgoraRtmTokenVO().apply {
          this.account = rtmAccount ?: userId
          this.rtmToken = rtmToken
          this.expireSecond = rtmExpire
        }
      }
    }
    agoraUserTokenVO = token
    if (rinoRemotePlayer != null) {
      runOnMain {
        try {
          RinoIPCSDK.updateToken(channelName, userId.toInt(), rtcToken)
          rinoRemotePlayer?.setToken(token)
          log.i("RTC token updated channel=$channelName uid=$userId")
        } catch (e: Exception) {
          log.e("updateToken", e)
        }
      }
    }
  }

  fun setRemoteUid(uid: Int) {
    remoteUid = if (uid > 0) uid else FV_DEVICE_REMOTE_ID
  }

  fun setRemoteChannelName(channelName: String) {
    remoteChannelName = channelName
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  private fun runOnMainBlocking(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
      return
    }
    val latch = CountDownLatch(1)
    var error: Throwable? = null
    mainHandler.post {
      try {
        block()
      } catch (t: Throwable) {
        error = t
      } finally {
        latch.countDown()
      }
    }
    latch.await(5, TimeUnit.SECONDS)
    error?.let { throw it }
  }

  fun attachPlayerContainer(container: ViewGroup) {
    if (playerContainer != container) {
      hasJoinChannelJob = false
    }
    playerContainer = container
  }

  fun reattachPlayerContainer(container: ViewGroup) {
    runOnMain {
      val token = agoraUserTokenVO
      val player = rinoRemotePlayer
      if (!hasJoinChannelJob || token == null) {
        log.d(
          "RTC view reattached skipped joined=$hasJoinChannelJob " +
            "token=${token != null} player=${player != null}",
        )
        return@runOnMain
      }

      if (player == null) {
        if (playerContainer === container) {
          log.d("RTC pending view attached")
          return@runOnMain
        }
        val generation = mediaGeneration.incrementAndGet()
        playerContainer = container
        log.i("RTC pending view changed; continue join")
        waitUntilReady(container, { isJoinCurrent(generation) }) {
          if (!isJoinCurrent(generation)) return@waitUntilReady
          initRinoPlayer(container, remoteUid)
          setEnableSpeakerphone(joinSpeakerOn)
        }
        return@runOnMain
      }

      log.i(
        "RTC view reattached; recreate player channel=${token.rtcToken?.channelName} " +
          "remoteUid=$remoteUid",
      )
      playerContainer = container
      container.removeAllViews()
      player.removeAllViews()
      rinoRemotePlayer = null
      initRinoPlayer(container, remoteUid)
    }
  }

  fun joinChannel(localUid: Int, isSpeakerOn: Boolean = true) {
    if (hasJoinChannelJob) {
      log.i("joinChannel skipped: already joined")
      return
    }
    val token = agoraUserTokenVO ?: throw IllegalStateException("RTC token not set")
    initIpc(token.agoraAppId ?: agoraAppId)
    val container = playerContainer ?: throw IllegalStateException("RTC view not ready")
    joinSpeakerOn = isSpeakerOn
    hasJoinChannelJob = true
    val generation = mediaGeneration.get()
    runOnMain {
      if (!isJoinCurrent(generation)) return@runOnMain
      log.i(
        "joinChannel(main) channel=${token.rtcToken?.channelName} localUid=$localUid remoteUid=$remoteUid speaker=$isSpeakerOn size=${container.width}x${container.height} attached=${container.isAttachedToWindow}",
      )
      waitUntilReady(container, { isJoinCurrent(generation) }) {
        if (!isJoinCurrent(generation)) return@waitUntilReady
        initRinoPlayer(container, remoteUid)
        setEnableSpeakerphone(isSpeakerOn)
      }
    }
  }

  private fun isJoinCurrent(generation: Long): Boolean {
    return generation == mediaGeneration.get() && hasJoinChannelJob && agoraUserTokenVO != null
  }

  private fun waitUntilReady(
    view: View,
    isCurrent: () -> Boolean,
    action: () -> Unit,
  ) {
    var started = false
    fun startIfReady(): Boolean {
      if (started) return true
      if (!isCurrent()) {
        started = true
        return true
      }
      if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return false
      started = true
      log.i("view ready size=${view.width}x${view.height}")
      action()
      return true
    }

    if (startIfReady()) return

    val attachListener = object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(v: View) {
        view.removeOnAttachStateChangeListener(this)
        view.requestLayout()
        startIfReady()
      }

      override fun onViewDetachedFromWindow(v: View) = Unit
    }
    view.addOnAttachStateChangeListener(attachListener)

    val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
      override fun onGlobalLayout() {
        if (!startIfReady()) return
        if (view.viewTreeObserver.isAlive) {
          view.viewTreeObserver.removeOnGlobalLayoutListener(this)
        }
      }
    }
    view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    view.requestLayout()
  }

  private fun initRinoPlayer(container: ViewGroup, remoteUid: Int) {
    val token = agoraUserTokenVO ?: return
    log.i("initRinoPlayer remoteUid=$remoteUid size=${container.width}x${container.height}")
    val playerContext = container.context ?: context
    rinoRemotePlayer = RinoRemotePlayer(playerContext, remoteUid, false).also { player ->
      player.token = token
      player.setMuteAudio(isMuteAudio)
      player.setAutoLeaveChannelOnDestroy(false)
    }
    container.removeAllViews()
    container.addView(
      rinoRemotePlayer,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )
    RtcViewLayout.layoutChildren(container)
    container.requestLayout()
  }

  fun leaveChannel() {
    mediaGeneration.incrementAndGet()
    runOnMainBlocking {
      releasePlayer()
      val channelName = agoraUserTokenVO?.rtcToken?.channelName
      val userId = agoraUserTokenVO?.userId
      if (!channelName.isNullOrEmpty() && !userId.isNullOrEmpty()) {
        try {
          RinoIPCSDK.leaveChannel(channelName, userId.toInt())
        } catch (e: Exception) {
          log.e("leaveChannel error", e)
        }
      }
      agoraUserTokenVO = null
      remoteChannelName = ""
      remoteUid = FV_DEVICE_REMOTE_ID
    }
  }

  private fun releasePlayer() {
    hasJoinChannelJob = false
    playerContainer?.removeAllViews()
    rinoRemotePlayer?.removeAllViews()
    rinoRemotePlayer = null
  }

  fun setMuteAudio(mute: Boolean) {
    log.i("setMuteAudio mute=$mute")
    isMuteAudio = mute
    runOnMain { rinoRemotePlayer?.setMuteAudio(mute) }
  }

  fun setMicEnabled(enable: Boolean) {
    log.i("setMicEnabled enable=$enable joined=$hasJoinChannelJob")
    isMicEnabled = enable
    if (!hasJoinChannelJob) return
    if (enable) startPushAudio() else stopPushAudio()
  }

  fun setEnableSpeakerphone(isOpen: Boolean): Int {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.isSpeakerphoneOn = isOpen
    return try {
      RinoIPCSDK.setEnableSpeakerphone(isOpen)
    } catch (e: Exception) {
      log.e("setEnableSpeakerphone", e)
      -1
    }
  }

  fun startPushAudio(localUid: Int = agoraUserTokenVO?.userId?.toIntOrNull() ?: 0): Int {
    val channelId = agoraUserTokenVO?.rtcToken?.channelName ?: return -2
    log.i("startPushAudio localUid=$localUid")
    return try {
      RinoIPCSDK.startPushAudioToChannel(channelId, localUid, dynamicAudioCodec)
    } catch (e: Exception) {
      log.e("startPushAudio", e)
      -2
    }
  }

  fun stopPushAudio(localUid: Int = agoraUserTokenVO?.userId?.toIntOrNull() ?: 0): Int {
    val channelId = agoraUserTokenVO?.rtcToken?.channelName ?: return -2
    return try {
      RinoIPCSDK.stopPushAudioToChannel(channelId, localUid)
    } catch (e: Exception) {
      log.e("stopPushAudio", e)
      -2
    }
  }

  fun takeSnapshot(filePath: String? = null, saveToGallery: Boolean = false): Int {
    val channelId = agoraUserTokenVO?.rtcToken?.channelName ?: return -1
    val localUid = agoraUserTokenVO?.userId?.toIntOrNull() ?: 0
    val path = filePath ?: File(
      context.cacheDir,
      "snapshot_${System.currentTimeMillis()}.png",
    ).absolutePath
    lastSnapshotPath = path
    return try {
      RinoIPCSDK.takeSnapshot(channelId, localUid, remoteUid, path, saveToGallery)
    } catch (e: Exception) {
      log.e("takeSnapshot", e)
      -2
    }
  }

  fun clearCache() {
    mediaGeneration.incrementAndGet()
    remoteChannelName = ""
    agoraUserTokenVO = null
    hasJoinChannelJob = false
  }

  fun destroy() {
    leaveChannel()
    runOnMainBlocking {
      eventEmitter?.removeListener(this)
      eventEmitter = null
      try {
        RinoIPCSDK.destroy()
        log.i("RinoIPCSDK destroy done")
      } catch (e: Exception) {
        log.e("RinoIPCSDK destroy failed", e)
      }
      agoraAppId = ""
    }
  }

  override fun onEvent(event: RinoIPCEventEmitter.RinoIPCEvent, ctx: Context) {
    if(event.eventType != RinoIPCEventEmitter.RinoIPCEventTypeEnum.onPlaybackAudioFrameBeforeMixing) {
      log.i("[event] ${event.eventType} data=${event.data}")
    }
    when (event.eventType) {
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onConnectionStateChanged -> {
        val state = (event.data?.get("state") as? Number)?.toInt() ?: 0
        val reason = (event.data?.get("reason") as? Number)?.toInt()
        emit(RtcEvent.ConnectionChanged, mapOf("state" to state, "reason" to reason))
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onJoinChannelSuccess,
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onRejoinChannelSuccess,
      -> {
        agoraUserTokenVO?.userId?.toIntOrNull()?.let { uid ->
          if (isMicEnabled) startPushAudio(uid) else stopPushAudio(uid)
        }
        emit(
          RtcEvent.JoinChannel,
          mapOf(
            "channel" to agoraUserTokenVO?.rtcToken?.channelName,
            "uid" to agoraUserTokenVO?.userId?.toIntOrNull(),
          ),
        )
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onFirstRemoteVideoFrame -> {
        emit(RtcEvent.FirstVideoFrame, event.data)
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onRemoteVideoStateChanged -> {
        val state = (event.data?.get("state") as? Number)?.toInt()
        val reason = (event.data?.get("reason") as? Number)?.toInt()
        emit(RtcEvent.VideoStateChanged, mapOf("state" to state, "reason" to reason))
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onLeaveChannel -> {
        hasJoinChannelJob = false
        emit(RtcEvent.LeaveChannel, emptyMap())
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onUserOffline -> {
        emit(RtcEvent.UserOffline, emptyMap())
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onSnapshotTaken -> {
        val errCode = (event.data?.get("errCode") as? Number)?.toInt() ?: -1
        emit(RtcEvent.SnapshotTaken, mapOf("result" to errCode, "path" to lastSnapshotPath))
      }
      RinoIPCEventEmitter.RinoIPCEventTypeEnum.onTokenPrivilegeWillExpire -> {
        emit(
          RtcEvent.TokenWillExpire,
          mapOf("channelName" to agoraUserTokenVO?.rtcToken?.channelName),
        )
      }
      else -> Unit
    }
  }
}
