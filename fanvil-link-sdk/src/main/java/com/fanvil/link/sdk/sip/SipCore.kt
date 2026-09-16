package com.fanvil.link.sdk.sip

import android.content.Context
import android.util.Log
import com.fanvil.link.sdk.call.CallState
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaDirection
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File
import java.io.FileOutputStream

/**
 * 瀵归綈 AndroidDoorAccess LinphoneManager / CoreContext 鐨勭簿绠€灏佽銆?
 * 浠呬繚鐣?RN 渚ч渶瑕佺殑 register / call / accept / hangup / 浜嬩欢銆?
 */
class SipCore(
  private val context: Context,
  private val emit: (event: String, payload: Map<String, Any?>) -> Unit,
) {
  companion object {
    private const val TAG = "SipCore"
    private const val EXTRA_MONITOR = "monitor"
    private const val CALL_INFO = "Call-Info"
  }

  private var core: Core? = null
  private var started = false
  val preferences = CorePreferences()

  @Volatile
  private var lastRegistrationState: SipRegistrationState? = null

  @Volatile
  private var lastCallBusinessState: CallState? = null

  @Volatile
  private var lastIncomingKey: String? = null

  private val listener = object : CoreListenerStub() {
    override fun onAccountRegistrationStateChanged(
      core: Core,
      account: org.linphone.core.Account,
      state: RegistrationState?,
      message: String,
    ) {
      // 只上报当前 defaultAccount，避免多 Account 残留导致刷屏
      if (account !== core.defaultAccount) return
      val mapped = when (state) {
        RegistrationState.None -> SipRegistrationState.None
        RegistrationState.Progress -> SipRegistrationState.Progress
        RegistrationState.Ok -> SipRegistrationState.Ok
        RegistrationState.Cleared -> SipRegistrationState.Cleared
        RegistrationState.Failed -> SipRegistrationState.Failed
        else -> SipRegistrationState.None
      }
      if (mapped == lastRegistrationState) return
      lastRegistrationState = mapped
      Log.i(TAG, "[event] onRegistration state=$mapped message=$message")
      emit("onRegistration", mapOf("state" to mapped, "message" to message))
    }

    override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
      val remote = call.remoteAddress
      val callLog = call.callLog
      val base = mapOf(
        "callId" to callLog.callId,
        "deviceId" to remote?.username,
        "startedAt" to callLog.startDate * 1000,
        "remoteUsername" to remote?.username,
        "remoteDisplayName" to remote?.displayName,
        "remoteAddress" to remote?.asStringUriOnly(),
      )
      Log.i(
        TAG,
        "[event] onCallStateChanged state=$state message=$message remote=${remote?.asStringUriOnly()}",
      )
      val businessState = when (state) {
        Call.State.IncomingReceived -> CallState.Incoming
        Call.State.IncomingEarlyMedia -> CallState.IncomingEarlyMedia
        Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> CallState.OutgoingInit
        Call.State.Connected, Call.State.StreamsRunning -> CallState.Connected
        Call.State.End -> CallState.End
        Call.State.Released -> CallState.Released
        Call.State.Error -> CallState.Error
        Call.State.UpdatedByRemote -> CallState.UpdatedByRemote
        else -> null
      } ?: return

      if (businessState == CallState.Incoming) {
        val key = remote?.asStringUriOnly().orEmpty()
        if (key == lastIncomingKey) return
        lastIncomingKey = key
      }

      if (businessState == lastCallBusinessState) return
      lastCallBusinessState = businessState
      emit("onCallStateChanged", base + ("state" to businessState))

      if (businessState == CallState.Released || businessState == CallState.Error) {
        lastCallBusinessState = null
        lastIncomingKey = null
      }
    }
  }

  fun initialize() {
    if (core != null) {
      Log.i(TAG, "initialize skipped (already initialized)")
      return
    }
    Log.i(TAG, "initialize")
    copyAssets()
    val configPath = File(context.filesDir, "linphonerc").absolutePath
    val factoryPath = File(context.filesDir, "linphonerc_factory").absolutePath
    val config = Factory.instance().createConfigWithFactory(configPath, factoryPath)
    val c = Factory.instance().createCoreWithConfig(config, context)
    c.addListener(listener)
    c.isVideoCaptureEnabled = true
    c.isVideoDisplayEnabled = true
    applyEarlyMedia(c)
    c.start()
    core = c
    started = true
    Log.i(TAG, "SipCore initialized")
  }

  fun setAcceptEarlyMedia(enabled: Boolean) {
    preferences.acceptEarlyMedia = enabled
    core?.let { applyEarlyMedia(it) }
    Log.i(TAG, "setAcceptEarlyMedia enabled=$enabled")
  }

  private fun applyEarlyMedia(c: Core) {
    c.config.setBool("sip", "incoming_calls_early_media", preferences.acceptEarlyMedia)
  }

  private fun copyAssets() {
    listOf("linphonerc_default" to "linphonerc", "linphonerc_factory" to "linphonerc_factory").forEach { (asset, dest) ->
      val out = File(context.filesDir, dest)
      if (out.exists()) return@forEach
      context.assets.open(asset).use { input ->
        FileOutputStream(out).use { output -> input.copyTo(output) }
      }
    }
  }

  private fun requireCore(): Core {
    if (core == null) initialize()
    return core ?: throw IllegalStateException("SIP core unavailable")
  }

  fun register(
    username: String,
    password: String = "",
    domain: String = "127.0.0.1",
    port: String = LoopBackManager.LB_LISTEN_PORT.toString(),
    realm: String = "",
    displayName: String? = "",
  ) {
    Log.i(
      TAG,
      "register username=$username domain=$domain port=$port realm=$realm displayName=$displayName",
    )
    val c = requireCore()
    val defaultAccount = c.defaultAccount
    if (defaultAccount != null && defaultAccount.findAuthInfo()?.username == username) {
      Log.i(TAG, "register skipped (same username already registered)")
      return
    }

    // 对齐 CoreContext：SIP 代理到 LoopBack 本地端口
    val proxyPort = port.toIntOrNull() ?: LoopBackManager.LB_LISTEN_PORT
    try {
      c.javaClass.getMethod("setStrictProxyPort", Int::class.javaPrimitiveType).invoke(c, proxyPort)
      Log.i(TAG, "setStrictProxyPort=$proxyPort")
    } catch (_: Throwable) {
      Log.w(TAG, "setStrictProxyPort unavailable")
    }

    // 清理历史 Account，避免多账号重复回调注册状态
    try {
      c.defaultAccount = null
      c.clearAccounts()
      c.clearAllAuthInfo()
    } catch (e: Exception) {
      Log.w(TAG, "clearAccounts failed", e)
    }
    lastRegistrationState = null

    val authInfo = Factory.instance().createAuthInfo(username, username, password, null, realm, domain)
    c.addAuthInfo(authInfo)

    val accountParams = c.createAccountParams()
    val identityRealm = realm.ifEmpty { domain }
    val identity = Factory.instance().createAddress("sip:$username@$identityRealm")
    if (!displayName.isNullOrEmpty()) {
      identity?.displayName = if (displayName.length > 64) displayName.take(64) + "..." else displayName
    }
    identity?.port = proxyPort
    accountParams.identityAddress = identity

    val server = Factory.instance().createAddress("sip:$domain")
    server?.port = proxyPort
    server?.transport = TransportType.Udp
    accountParams.serverAddress = server
    accountParams.isRegisterEnabled = true
    if (realm.isNotEmpty()) {
      accountParams.isOutboundProxyEnabled = true
      accountParams.realm = realm
    }

    val account = c.createAccount(accountParams)
    c.addAccount(account)
    c.defaultAccount = account

    val policy = c.videoActivationPolicy
    policy.automaticallyAccept = true
    c.videoActivationPolicy = policy
    c.setPreferredVideoDefinitionByName("VGA")
    if (!started) {
      c.start()
      started = true
    }
    Log.i(TAG, "register account added identity=sip:$username@$identityRealm:$proxyPort")
  }

  fun unregister() {
    Log.i(TAG, "unregister")
    val account = core?.defaultAccount ?: return
    val clone = account.params.clone()
    clone.isRegisterEnabled = false
    account.params = clone
  }

  fun delete() {
    val c = core ?: return
    Log.i(TAG, "delete accounts=${c.accountList.size}")
    c.accountList.toList().forEach { c.removeAccount(it) }
    c.clearAccounts()
    c.clearAllAuthInfo()
    lastRegistrationState = null
  }

  fun makeCall(username: String, displayName: String? = "", type: String = "video"): String {
    Log.i(TAG, "makeCall username=$username displayName=$displayName type=$type")
    val c = requireCore()
    val domain = c.defaultAccount?.params?.identityAddress?.domain ?: "127.0.0.1"
    val remoteSipUri = if (username.startsWith("sip:")) username else "sip:$username@$domain"
    val remoteAddress = Factory.instance().createAddress(remoteSipUri)
      ?: throw IllegalArgumentException("Invalid SIP address: $remoteSipUri")
    val params = c.createCallParams(null)
      ?: throw IllegalStateException("SIP call params unavailable")

    when (type) {
      "audio" -> {
        params.isVideoEnabled = false
      }
      "monitor" -> {
        params.isVideoEnabled = true
        params.addCustomHeader(CALL_INFO, EXTRA_MONITOR)
        c.isVideoCaptureEnabled = true
        c.isVideoDisplayEnabled = true
      }
      else -> {
        params.isVideoEnabled = true
        params.audioDirection = MediaDirection.SendRecv
        params.videoDirection = MediaDirection.RecvOnly
        c.isVideoCaptureEnabled = true
        c.isVideoDisplayEnabled = true
      }
    }
    params.sessionName = displayName
    params.mediaEncryption = MediaEncryption.None
    Log.i(TAG, "makeCall invite $remoteSipUri video=${params.isVideoEnabled}")
    val call = c.inviteAddressWithParams(remoteAddress, params)
      ?: throw IllegalStateException("SIP call unavailable")
    return call.callLog.callId?.takeIf { it.isNotEmpty() }
      ?: throw IllegalStateException("SIP call ID unavailable")
  }

  fun accept() {
    Log.i(TAG, "accept")
    val c = core ?: run {
      Log.w(TAG, "accept skipped: core null")
      return
    }
    if (c.callsNb == 0) {
      Log.w(TAG, "accept skipped: no calls")
      return
    }
    val call = c.currentCall ?: c.calls.firstOrNull() ?: run {
      Log.w(TAG, "accept skipped: no call object")
      return
    }
    val params = c.createCallParams(call)
    call.acceptWithParams(params)
  }

  fun hangup(): Boolean {
    val c = core ?: run {
      Log.w(TAG, "hangup skipped: core null")
      return false
    }
    val result = when {
      c.callsNb == 0 -> false
      c.currentCall != null -> {
        c.currentCall?.terminate()
        true
      }
      else -> {
        c.terminateAllCalls()
        true
      }
    }
    Log.i(TAG, "hangup result=$result callsNb=${c.callsNb}")
    return result
  }

  fun setMicEnabled(enabled: Boolean) {
    Log.i(TAG, "setMicEnabled enabled=$enabled")
    core?.isMicEnabled = enabled
  }

  fun setSpeakerEnabled(enabled: Boolean) {
    Log.i(TAG, "setSpeakerEnabled enabled=$enabled")
    // Linphone audio route: use Core API when available
    try {
      val c = core ?: return
      if (enabled) {
        c.outputAudioDevice = c.audioDevices.firstOrNull {
          it.type.name.contains("Speaker", ignoreCase = true)
        } ?: c.outputAudioDevice
      } else {
        c.outputAudioDevice = c.audioDevices.firstOrNull {
          it.type.name.contains("Earpiece", ignoreCase = true) ||
            it.type.name.contains("Headset", ignoreCase = true)
        } ?: c.outputAudioDevice
      }
    } catch (e: Exception) {
      Log.w(TAG, "setSpeakerEnabled fallback", e)
    }
  }

  fun sendDtmf(dtmf: String) {
    Log.i(TAG, "sendDtmf dtmf=$dtmf")
    val call = core?.currentCall ?: run {
      Log.w(TAG, "sendDtmf skipped: no current call")
      return
    }
    if (dtmf.isNotEmpty()) {
      call.sendDtmf(dtmf[0])
    }
  }

  fun isCalling(): Boolean = (core?.callsNb ?: 0) > 0

  fun destroy() {
    Log.i(TAG, "destroy")
    try {
      core?.removeListener(listener)
      core?.stop()
    } catch (_: Exception) {
    }
    core = null
    started = false
    lastRegistrationState = null
    lastCallBusinessState = null
    lastIncomingKey = null
  }
}

private operator fun Map<String, Any?>.plus(pair: Pair<String, Any?>): Map<String, Any?> {
  return toMutableMap().apply { put(pair.first, pair.second) }
}
