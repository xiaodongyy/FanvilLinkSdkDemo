# FvCloudTalkSDK

原生 Android SDK：MQTT + SIP + RTC。入口 `com.fanvil.link.sdk.FvCloudTalkSDK`。

媒体入会由 SDK 在通话状态变化时自动完成，接入方不要自己 join。

---

## 接入

1. 申请 `RECORD_AUDIO`、`CAMERA`
2. `FrameLayout` 里 `addView(FvCloudTalkSDK.getRtcView(this))`
3. `addListener`
4. `initialize`，等 MQTT / SIP 回调后再呼叫
5. `onDestroy`：`removeListener` + `destroy`

未初始化调用业务 API 会 Toast `SDK not initialized` 并直接返回。

---

## FvSdkConfig

| 字段 | 说明 |
|---|---|
| `userId` | 用户 ID，MQTT clientId |
| `agoraId` | SIP / RTC 账号 |
| `agoraAppId` | RTC AppId |
| `accessToken` | MQTT 密码 |
| `mqttUrl` | 如 `ssl://host:8883` |
| `mqttUserName` | MQTT 用户名 |
| `displayName` | 可选 SIP 显示名 |

---

## API

### 生命周期

```kotlin
fun initialize(context: Context, config: FvSdkConfig)
fun destroy()
fun isReady(): Boolean   // 已 initialize 且 MQTT 已连接
fun reconnect()
fun publish(topic: String, payload: String)
```

### 监听 / 视频

```kotlin
fun addListener(listener: FvSdkListener)
fun removeListener(listener: FvSdkListener)
fun getRtcView(context: Context): FvRtcVideoView
fun releaseRtcView()
```

`getRtcView` 由 SDK 创建。重复获取会先从旧 parent 摘下，返回同一实例。页面切走会自动释放；花屏等特殊情况可调 `releaseRtcView()`，再 `getRtcView` 新建。

### 呼叫

```kotlin
fun startCall(sipUsername: String, isVideo: Boolean = true)
fun startMonitor(sipUsername: String, timeoutSeconds: Int = 30)
fun acceptCall()
fun rejectCall()
fun endCall()
fun isCalling(): Boolean
fun isMonitorMode(): Boolean
fun isMediaJoined(): Boolean
fun getActiveCall(): CallSession?
```

`CallSession`：`callId`、`deviceId`、`startedAt`。`callId` 为 Linphone 实际创建的 SIP Call-ID。

- `startCall`：`isVideo = true` 视频呼叫，`false` 语音呼叫
- `startMonitor`：监控（关麦、带视频），与 `startCall` 独立；默认 30s 倒计时，到 0 自动挂断；`timeoutSeconds <= 0` 不加倒计时
- `Incoming`：UI 提示来电
- `IncomingEarlyMedia` 且 RTC token 就绪：自动预览（关麦）；token 晚到会补入会
- `Connected`：自动加入媒体

### 媒体 / 开门

```kotlin
fun setMuted(muted: Boolean)
fun setSpeakerOn(enabled: Boolean)
fun takeSnapshot(filePath: String? = null, saveToGallery: Boolean = false): Int  // 失败 -1
fun clearRtcCache()
fun openDoor(mac: String, whichDoor: Int = 1, doorNoList: List<Int>? = null)
```

---

## FvSdkListener

全部有默认空实现。

```kotlin
fun onMqttConnectionChanged(status: String, code: Int?, message: String?, reconnect: Boolean?)
fun onMqttMessage(topic: String, payload: String)
fun onSipOutgoing(topic: String, payload: String)
fun onSipRegistration(state: SipRegistrationState, message: String)
fun onCallStateChanged(
    state: CallState,
    remoteUsername: String?,
    remoteDisplayName: String?,
    remoteAddress: String?,
)
fun onRtcEvent(event: RtcEvent, payload: Map<String, Any?>)
fun onMonitorCountdown(remainSeconds: Int)
```

**CallState：** `Incoming` `IncomingEarlyMedia` `OutgoingInit` `Connected` `End` `Released` `Error` `UpdatedByRemote`

**SipRegistrationState：** `None` `Progress` `Ok` `Cleared` `Failed`

**RtcEvent：** `ConnectionChanged` `JoinChannel` `FirstVideoFrame` `VideoStateChanged` `LeaveChannel` `UserOffline` `SnapshotTaken` `TokenWillExpire`

---

## 示例

```kotlin
class MainActivity : Activity() {
    private val listener = object : FvSdkListener {
        override fun onCallStateChanged(
            state: CallState,
            remoteUsername: String?,
            remoteDisplayName: String?,
            remoteAddress: String?,
        ) {
            if (state == CallState.Incoming) {
                // acceptCall() / rejectCall()
            }
        }
        override fun onMonitorCountdown(remainSeconds: Int) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<FrameLayout>(R.id.rtcView).addView(
            FvCloudTalkSDK.getRtcView(this),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        FvCloudTalkSDK.addListener(listener)
        FvCloudTalkSDK.initialize(
            this,
            FvSdkConfig(
                userId = userId,
                agoraId = agoraId,
                agoraAppId = appId,
                accessToken = token,
                mqttUrl = mqttUrl,
                mqttUserName = mqttUser,
                displayName = "Android Demo",
            ),
        )

        FvCloudTalkSDK.startCall(target)                    // 视频
        FvCloudTalkSDK.startCall(target, isVideo = false)   // 语音
        FvCloudTalkSDK.startMonitor(target)                 // 监控，30s
        FvCloudTalkSDK.startMonitor(target, timeoutSeconds = 0)
        FvCloudTalkSDK.acceptCall()
        FvCloudTalkSDK.endCall()
        FvCloudTalkSDK.setMuted(true)
        FvCloudTalkSDK.setSpeakerOn(true)
        FvCloudTalkSDK.openDoor(mac)
    }

    override fun onDestroy() {
        FvCloudTalkSDK.removeListener(listener)
        FvCloudTalkSDK.destroy()
        super.onDestroy()
    }
}
```

```xml
<FrameLayout
    android:id="@+id/rtcView"
    android:layout_width="match_parent"
    android:layout_height="240dp" />
```

权限：`INTERNET` `ACCESS_NETWORK_STATE` `RECORD_AUDIO` `CAMERA` `MODIFY_AUDIO_SETTINGS`。麦克风和摄像头需运行时申请。
