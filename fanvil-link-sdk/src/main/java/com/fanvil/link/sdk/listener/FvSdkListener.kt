package com.fanvil.link.sdk.listener

import com.fanvil.link.sdk.call.CallState
import com.fanvil.link.sdk.rtc.RtcEvent
import com.fanvil.link.sdk.sip.SipRegistrationState

interface FvSdkListener {
  fun onMqttConnectionChanged(status: String, code: Int?, message: String?, reconnect: Boolean?) {}
  fun onMqttMessage(topic: String, payload: String) {}
  fun onSipOutgoing(topic: String, payload: String) {}
  fun onSipRegistration(state: SipRegistrationState, message: String) {}
  fun onCallStateChanged(
    state: CallState,
    remoteUsername: String?,
    remoteDisplayName: String?,
    remoteAddress: String?,
  ) {}
  fun onRtcEvent(event: RtcEvent, payload: Map<String, Any?>) {}
  fun onMonitorCountdown(remainSeconds: Int) {}
}
