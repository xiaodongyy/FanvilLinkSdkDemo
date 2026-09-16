package com.example.fanviltalksdkdemo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.app.ActivityCompat
import com.fanvil.link.sdk.FvCloudTalkSDK
import com.fanvil.link.sdk.FvSdkConfig
import com.fanvil.link.sdk.call.CallState
import com.fanvil.link.sdk.listener.FvSdkListener
import com.fanvil.link.sdk.rtc.RtcEvent
import com.fanvil.link.sdk.sip.SipRegistrationState
import com.fanvil.link.sdk.utils.FvlLogger

class MainActivity : Activity() {
    companion object {
        const val TAG = "Fvl_MainActivity"
    }

    private lateinit var statusText: TextView

    private val sdkListener = object : FvSdkListener {
        override fun onMqttConnectionChanged(
            status: String,
            code: Int?,
            message: String?,
            reconnect: Boolean?
        ) {
            showStatus(getString(R.string.status_mqtt, status, message.orEmpty()))
        }

        override fun onSipRegistration(state: SipRegistrationState, message: String) {
            showStatus(getString(R.string.status_sip, state.toString(), message))
        }

        override fun onSipOutgoing(topic: String, payload: String) {
            Log.i(TAG, "SIP outgoing topic=$topic payload=$payload")
        }

        override fun onCallStateChanged(
            state: CallState,
            remoteUsername: String?,
            remoteDisplayName: String?,
            remoteAddress: String?
        ) {
            when (state) {
                CallState.Incoming -> // 来电振铃
                    showStatus(
                        getString(
                            R.string.status_incoming,
                            remoteDisplayName ?: remoteUsername.orEmpty(),
                        )
                    )
                CallState.IncomingEarlyMedia -> // 来电早媒体（预览）
                    showStatus(getString(R.string.status_early_media, remoteUsername.orEmpty()))
                CallState.OutgoingInit -> // 去电发起
                    showStatus(getString(R.string.status_outgoing, remoteUsername.orEmpty()))
                CallState.Connected -> // 通话已接通
                    showStatus(getString(R.string.status_connected, remoteUsername.orEmpty()))
                CallState.End -> // 通话结束中
                    showStatus(getString(R.string.status_call_end))
                CallState.Released -> // 通话资源已释放
                    showStatus(getString(R.string.status_released))
                CallState.Error -> // 通话失败
                    showStatus(getString(R.string.status_call_error))
                CallState.UpdatedByRemote -> // 对端更新媒体（如音视频切换）
                    showStatus(getString(R.string.status_updated_by_remote))
            }
        }

        override fun onRtcEvent(event: RtcEvent, payload: Map<String, Any?>) {
            when (event) {
                RtcEvent.ConnectionChanged -> // RTC 连接状态变化 payload: state, reason
                    Log.e(TAG, "RTC连接变化 payload=$payload")
                RtcEvent.JoinChannel -> // 加入频道成功
                    Log.e(TAG, "加入频道 payload=$payload")
                RtcEvent.FirstVideoFrame -> // 首帧视频已渲染
                    Log.e(TAG, "首帧视频 payload=$payload")
                RtcEvent.VideoStateChanged -> // 远端视频状态变化 payload: state, reason
                    Log.e(TAG, "视频状态 payload=$payload")
                RtcEvent.LeaveChannel -> // 已离开频道
                    Log.e(TAG, "离开频道")
                RtcEvent.UserOffline -> // 远端用户离线
                    Log.e(TAG, "远端离线")
                RtcEvent.SnapshotTaken -> // 截图完成 payload: result, path
                    Log.e(TAG, "截图 payload=$payload")
                RtcEvent.TokenWillExpire -> // Token 即将过期
                    Log.e(TAG, "Token即将过期 payload=$payload")
            }
        }

        override fun onMonitorCountdown(remainSeconds: Int) {
            showStatus(getString(R.string.status_monitor_remain, remainSeconds))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        findViewById<FrameLayout>(R.id.rtcView).addView(
            FvCloudTalkSDK.getRtcView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        FvCloudTalkSDK.addListener(sdkListener)
        requestMediaPermissions()

        findViewById<Button>(R.id.initButton).setOnClickListener {
            val userId = textOf(R.id.userIdInput)
            val agoraId = textOf(R.id.agoraIdInput)
            val appId = textOf(R.id.agoraAppIdInput)
            val token = textOf(R.id.accessTokenInput)
            val mqttUrl = textOf(R.id.mqttUrlInput)
            val mqttUser = textOf(R.id.mqttUserInput)
            if (listOf(userId, agoraId, appId, token, mqttUrl, mqttUser).any { it.isBlank() }) {
                showStatus(getString(R.string.status_fill_init_params))
                return@setOnClickListener
            }
            FvCloudTalkSDK.initialize(
                this,
                FvSdkConfig(
                    userId = userId,
                    agoraId = agoraId,
                    agoraAppId = appId,
                    accessToken = token,
                    mqttUrl = mqttUrl,
                    mqttUserName = mqttUser,
                    displayName = getString(R.string.display_name_demo),
                ),
            )
            FvlLogger.setGlobalLevel(FvlLogger.DEBUG)
            showStatus(getString(R.string.status_initializing))
        }

        findViewById<Button>(R.id.callButton).setOnClickListener {
            val target = textOf(R.id.sipUsernameInput)
            if (target.isBlank()) {
                showStatus(getString(R.string.status_enter_sip_username))
                return@setOnClickListener
            }
            FvCloudTalkSDK.startCall(target, isVideo = true)
            showStatus(getString(R.string.status_calling, target))
        }

        findViewById<Button>(R.id.monitorButton).setOnClickListener {
            val target = textOf(R.id.sipUsernameInput)
            if (target.isBlank()) {
                showStatus(getString(R.string.status_enter_monitor_sip))
                return@setOnClickListener
            }
            FvCloudTalkSDK.startMonitor(target)
            showStatus(getString(R.string.status_monitoring, target))
        }

        findViewById<Button>(R.id.acceptButton).setOnClickListener {
            FvCloudTalkSDK.acceptCall()
        }

        findViewById<Button>(R.id.endButton).setOnClickListener {
            FvCloudTalkSDK.endCall()
            showStatus(getString(R.string.status_ended))
        }

        findViewById<ToggleButton>(R.id.muteButton).setOnCheckedChangeListener { _, checked ->
            FvCloudTalkSDK.setMuted(checked)
        }
        findViewById<ToggleButton>(R.id.speakerButton).setOnCheckedChangeListener { _, checked ->
            FvCloudTalkSDK.setSpeakerOn(checked)
        }

        findViewById<Button>(R.id.openDoorButton).setOnClickListener {
            val mac = textOf(R.id.macInput)
            if (mac.isBlank()) {
                showStatus(getString(R.string.status_enter_mac))
                return@setOnClickListener
            }
            FvCloudTalkSDK.openDoor(mac)
            showStatus(getString(R.string.status_opening_door, mac))
        }
    }

    private fun textOf(id: Int): String = findViewById<EditText>(id).text.toString().trim()

    private fun showStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun requestMediaPermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (permissions.any {
                ActivityCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    override fun onDestroy() {
        FvCloudTalkSDK.removeListener(sdkListener)
        FvCloudTalkSDK.destroy()
        super.onDestroy()
    }
}
