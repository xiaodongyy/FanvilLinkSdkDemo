package com.fanvil.link.sdk.bridge

import android.view.ViewGroup
import com.fanvil.link.sdk.mqtt.MqttClientHolder
import com.fanvil.link.sdk.mqtt.MqttTopics
import com.fanvil.link.sdk.rtc.RinoRtcEngine
import com.fanvil.link.sdk.rtc.RtcViewRegistry
import com.fanvil.link.sdk.sip.LoopBackManager
import com.fanvil.link.sdk.sip.SipCore
import com.fanvil.link.sdk.utils.FvlLogger
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对齐 TS sip-mqtt-bridge：SIP ↔ MQTT ↔ RTC token。
 */
class SipMqttBridge(
  private val mqtt: MqttClientHolder,
  private val sip: SipCore,
  private val rtc: RinoRtcEngine,
) {
  private val log = FvlLogger.getLogger("SipMqttBridge")

  private var agoraId: String = ""
  private var userId: String = ""
  private var agoraAppId: String = ""
  private var displayName: String? = null

  private val started = AtomicBoolean(false)
  @Volatile private var pendingRtcChannel = ""
  @Volatile private var rtcCallId = ""
  @Volatile private var endedRtcCallId = ""
  @Volatile private var rtcRemoteUid = RinoRtcEngine.FV_DEVICE_REMOTE_ID
  @Volatile private var rtcReady: RtcReadyState? = null
  var onRtcTokenReady: ((RtcReadyState) -> Unit)? = null
  var onSipOutgoing: ((topic: String, payload: String) -> Unit)? = null

  data class RtcReadyState(
    val callId: String,
    val channelName: String,
    val localUid: Int,
    val remoteUid: Int,
    val agoraAppId: String,
    val userId: String,
    val rtcToken: String,
    val expireSecond: Int,
    val rtmAccount: String?,
    val rtmToken: String?,
    val rtmExpire: Int,
  )

  fun start(agoraId: String, userId: String, agoraAppId: String, displayName: String?) {
    this.agoraId = agoraId
    this.userId = userId
    this.agoraAppId = agoraAppId
    this.displayName = displayName

    if (started.compareAndSet(false, true)) {
      LoopBackManager.setOutgoingHandler { sipBody, from, to, callId, method ->
        val ready = rtcReady
        val channelName = if (rtcCallId == callId) ready?.channelName.orEmpty() else ""
        val bean = JSONObject()
          .put("version", "1.0.0")
          .put("callId", callId)
          .put("sipType", method)
          .put("channelName", channelName)
          .put("msgId", "app_${System.currentTimeMillis()}")
          .put("from", from)
          .put("to", to)
          .put("aid", agoraId.toLongOrNull() ?: 0)
          .put("sipBody", sipBody)
        val topic = MqttTopics.sipUp(agoraId)
        val payload = bean.toString()
        try {
          mqtt.publish(topic, payload)
          onSipOutgoing?.invoke(topic, payload)
        } catch (e: Exception) {
          log.w("publish sip/up failed", e)
        }
      }
      LoopBackManager.init(LoopBackManager.LB_LISTEN_PORT)
      sip.initialize()
    }

    LoopBackManager.notifyMqttConnected(true)
    sip.register(
      username = agoraId,
      displayName = displayName ?: agoraId,
      domain = "127.0.0.1",
      port = LoopBackManager.LB_LISTEN_PORT.toString(),
    )
  }

  fun onMqttMessage(topic: String, payload: String) {
    if (userId.isEmpty() || agoraId.isEmpty()) return
    when (topic) {
      MqttTopics.tokenAck(userId) -> handleRtcTokenAck(payload)
      MqttTopics.msgCallDown(agoraId) -> {
        try {
          val message = JSONObject(payload)
          if (message.optString("msgType") == "channelChange") {
            val channelName = message.optJSONObject("data")?.optString("channelName").orEmpty()
            if (channelName.isNotEmpty()) {
              requestRtcToken(channelName, force = true)
            }
          }
        } catch (_: Exception) {
        }
      }
      MqttTopics.sipDown(agoraId) -> {
        val msg = try {
          JSONObject(payload)
        } catch (_: Exception) {
          null
        }
        val sipBody = parseSipBody(msg, payload) ?: return
        val from = msg?.optString("from").orEmpty()
        val to = msg?.optString("to").orEmpty()
        val sipType = msg?.optInt("sipType", -1) ?: -1
        val aid = msg?.optInt("aid", 0) ?: 0
        val channelName = msg?.optString("channelName").orEmpty()
        val callId = msg?.optString("callId").orEmpty()

        // sipType=1 且 to 是自己 → 来电，按 sipBody 视频方向决定是否开预览
        if (sipType == 1 && to == agoraId) {
          val enableEarlyMedia = parseEnableEarlyMedia(sipBody)
          log.i("incoming from=$from enableEarlyMedia=$enableEarlyMedia")
          sip.setAcceptEarlyMedia(enableEarlyMedia)
        }

        if (aid > 0) rtcRemoteUid = aid
        if (channelName.isNotEmpty()) requestRtcToken(channelName, callId)
        LoopBackManager.feedMqttSipIncoming(sipBody)
      }
    }
  }

  fun onMqttConnectionChanged(connected: Boolean) {
    LoopBackManager.notifyMqttConnected(connected)
  }

  @Synchronized
  fun joinOutgoingCallRtc(
    callId: String,
    videoContainer: ViewGroup? = null,
    isSpeakerOn: Boolean = true,
    micEnabled: Boolean = true,
  ): Boolean {
    val ready = rtcReady
    if (ready == null || ready.callId != callId || callId == endedRtcCallId) {
      log.d(
        "joinOutgoingCallRtc pending callId=$callId " +
          "readyCallId=${ready?.callId} endedCallId=$endedRtcCallId",
      )
      return false
    }
    if (!applyRtcToken(ready)) return false
    rtc.setRemoteUid(ready.remoteUid)
    rtc.setRemoteChannelName(ready.channelName)
    rtc.setMicEnabled(micEnabled)
    val container = videoContainer ?: RtcViewRegistry.current
    if (container == null) {
      log.d("joinOutgoingCallRtc pending: FvRtcVideoView not mounted callId=$callId")
      return false
    }
    rtc.attachPlayerContainer(container)
    rtc.joinChannel(ready.localUid, isSpeakerOn)
    return true
  }

  fun stop() {
    LoopBackManager.setOutgoingHandler(null)
    LoopBackManager.uninit()
    started.set(false)
    resetRtcSession()
    onRtcTokenReady = null
    onSipOutgoing = null
  }

  @Synchronized
  fun resetRtcSession() {
    if (rtcCallId.isNotEmpty()) endedRtcCallId = rtcCallId
    pendingRtcChannel = ""
    rtcCallId = ""
    rtcReady = null
    rtcRemoteUid = RinoRtcEngine.FV_DEVICE_REMOTE_ID
  }

  fun getChannelName(): String =
    rtcReady?.channelName?.takeIf { it.isNotEmpty() } ?: pendingRtcChannel

  fun isRtcReady(callId: String? = null): Boolean {
    val ready = rtcReady ?: return false
    return callId.isNullOrEmpty() || ready.callId == callId
  }

  @Synchronized
  fun applyRtcToken(ready: RtcReadyState): Boolean {
    if (rtcReady != ready || ready.callId == endedRtcCallId) return false
    rtc.initIpc(ready.agoraAppId)
    rtc.setToken(
      agoraAppId = ready.agoraAppId,
      userId = ready.userId,
      channelName = ready.channelName,
      uid = ready.localUid,
      rtcToken = ready.rtcToken,
      expireSecond = ready.expireSecond,
      rtmAccount = ready.rtmAccount,
      rtmToken = ready.rtmToken,
      rtmExpire = ready.rtmExpire,
    )
    return true
  }

  @Synchronized
  private fun requestRtcToken(
    channelName: String,
    callId: String = rtcCallId,
    force: Boolean = false,
  ) {
    if (callId.isNotEmpty() && callId == endedRtcCallId) return
    if (callId.isNotEmpty() && rtcCallId != callId) {
      rtcCallId = callId
      pendingRtcChannel = ""
      rtcReady = null
    }
    if (userId.isEmpty() || channelName.isEmpty()) return
    if (!force && pendingRtcChannel == channelName) return
    pendingRtcChannel = channelName
    rtcReady = null
    val body = JSONObject()
      .put("version", "1.0.0")
      .put("channelName", channelName)
      .put("aid", agoraId.toLongOrNull() ?: 0)
      .put("msgId", "app_${System.currentTimeMillis()}")
      .put("rawData", "")
    try {
      mqtt.publish(MqttTopics.tokenRequest(userId), body.toString())
    } catch (e: Exception) {
      pendingRtcChannel = ""
      log.w("token request failed", e)
    }
  }

  @Synchronized
  private fun handleRtcTokenAck(payload: String) {
    try {
      val parsed = JSONObject(payload)
      val data = parsed.optJSONObject("data") ?: return
      val rtcTokenJson = data.optJSONObject("rtcToken") ?: return
      val channelName = rtcTokenJson.optString("channelName")
      val token = rtcTokenJson.optString("rtcToken")
      val localUid = agoraId.toIntOrNull() ?: 0
      if (channelName.isEmpty() || token.isEmpty() || localUid <= 0 || agoraAppId.isEmpty()) return
      if (pendingRtcChannel.isEmpty() || channelName != pendingRtcChannel) return

      val rtm = data.optJSONObject("rtmToken")
      val callId = rtcCallId
      if (callId.isEmpty() || callId == endedRtcCallId) return
      val ready = RtcReadyState(
        callId = callId,
        channelName = channelName,
        localUid = localUid,
        remoteUid = rtcRemoteUid,
        agoraAppId = agoraAppId,
        userId = localUid.toString(),
        rtcToken = token,
        expireSecond = rtcTokenJson.optInt("expireSecond", 3600),
        rtmAccount = rtm?.optString("account"),
        rtmToken = rtm?.optString("rtmToken"),
        rtmExpire = rtm?.optInt("expireSecond", 3600) ?: 3600,
      )
      rtcReady = ready
      onRtcTokenReady?.invoke(ready)
    } catch (e: Exception) {
      log.w("handleRtcTokenAck", e)
    }
  }

  private fun parseSipBody(parsed: JSONObject?, payload: String): String? {
    if (parsed != null) {
      val body = when {
        parsed.has("sipBody") -> parsed.optString("sipBody")
        parsed.optJSONObject("data")?.has("sipBody") == true ->
          parsed.optJSONObject("data")!!.optString("sipBody")
        else -> ""
      }
      if (body.isNotEmpty()) return body
    }
    return if (payload.contains("SIP/") || payload.startsWith("INVITE")) payload else null
  }

  /** P-Early-Media: supported + m=video 且非 recvonly/inactive → 可开预览 */
  private fun parseEnableEarlyMedia(sipBody: String): Boolean {
    val hasEarlyMedia = sipBody.lineSequence().any { line ->
      line.startsWith("P-Early-Media", ignoreCase = true) &&
        line.contains("supported", ignoreCase = true)
    }
    if (!hasEarlyMedia || !sipBody.contains("m=video")) return false
    val videoLine = sipBody.substring(sipBody.indexOf("m=video"))
    return !(videoLine.contains("a=recvonly") || videoLine.contains("a=inactive"))
  }
}
