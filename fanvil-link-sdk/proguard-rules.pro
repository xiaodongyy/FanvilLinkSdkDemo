-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes *Annotation*
-dontwarn javax.annotation.**

# 公开 API
-keep class com.fanvil.link.sdk.FvCloudTalkSDK { *; }
-keep class com.fanvil.link.sdk.FvSdkConfig { *; }
-keep class com.fanvil.link.sdk.listener.FvSdkListener { *; }
-keep class com.fanvil.link.sdk.call.CallState { *; }
-keep class com.fanvil.link.sdk.call.CallSession { *; }
-keep class com.fanvil.link.sdk.sip.SipRegistrationState { *; }
-keep class com.fanvil.link.sdk.rtc.RtcEvent { *; }
-keep class com.fanvil.link.sdk.rtc.FvRtcVideoView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    *;
}
-keep class com.fanvil.link.sdk.utils.FvlLogger { *; }
-keep class com.fanvil.link.sdk.utils.FvlLogger$* { *; }

# JNI / native
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.fanvil.link.sdk.sip.LoopBackManager { *; }

# MQTT（api 打进 AAR）
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Rino / Agora（反射与 so）
-keep class com.smart.rinoiot.** { *; }
-dontwarn com.smart.rinoiot.**
-keep class io.agora.** { *; }
-dontwarn io.agora.**

# 宿主提供，编译期可见
-dontwarn org.linphone.**
-keep class org.linphone.** { *; }
