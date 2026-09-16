package com.fanvil.link.sdk.call

import com.fanvil.link.sdk.rtc.RinoRtcEngine
import com.fanvil.link.sdk.sip.SipCore
import com.fanvil.link.sdk.utils.FvlLogger

data class CallSession(
  val callId: String,
  val deviceId: String,
  val startedAt: Long = System.currentTimeMillis(),
)

object CallService {
  private val log = FvlLogger.getLogger("CallService")

  fun startCall(
    sip: SipCore,
    sipUsername: String,
    displayName: String? = null,
    type: String = "video",
  ): CallSession {
    log.i("startCall sipUsername=$sipUsername type=$type")
    val callId = sip.makeCall(username = sipUsername, displayName = displayName, type = type)
    return CallSession(callId = callId, deviceId = sipUsername)
  }

  fun accept(sip: SipCore) {
    log.i("accept")
    sip.accept()
  }

  fun hangup(sip: SipCore, rtc: RinoRtcEngine?) {
    log.i("hangup")
    sip.hangup()
    try {
      rtc?.leaveChannel()
    } catch (e: Exception) {
      log.w("hangup leaveChannel failed", e)
    }
  }

  fun setMuted(sip: SipCore, rtc: RinoRtcEngine?, muted: Boolean) {
    log.i("setMuted muted=$muted")
    sip.setMicEnabled(!muted)
    try {
      rtc?.setMuteAudio(muted)
    } catch (e: Exception) {
      log.w("setMuted rtc failed", e)
    }
  }

  fun setSpeakerOn(sip: SipCore, rtc: RinoRtcEngine?, enabled: Boolean) {
    log.i("setSpeakerOn enabled=$enabled")
    sip.setSpeakerEnabled(enabled)
    try {
      rtc?.setEnableSpeakerphone(enabled)
    } catch (e: Exception) {
      log.w("setSpeakerOn rtc failed", e)
    }
  }
}
