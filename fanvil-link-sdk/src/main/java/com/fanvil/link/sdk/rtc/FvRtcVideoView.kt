package com.fanvil.link.sdk.rtc

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.fanvil.link.sdk.FvCloudTalkSDK

object RtcViewLayout {
  fun layoutChildren(container: ViewGroup) {
    val w = container.width
    val h = container.height
    if (w <= 0 || h <= 0) return
    for (i in 0 until container.childCount) {
      val child = container.getChildAt(i)
      child.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
      )
      child.layout(0, 0, w, h)
    }
  }
}

/**
 * 原生工程视频容器（非 ExpoView）。
 */
class FvRtcVideoView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
  companion object {
    private const val DETACH_CONFIRM_MS = 100L
  }

  private val detachConfirm = Runnable {
    if (!isAttachedToWindow) {
      FvCloudTalkSDK.onRtcViewDetached(this)
    }
  }

  init {
    clipChildren = false
    clipToPadding = false
    RtcViewRegistry.current = this
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    removeCallbacks(detachConfirm)
    RtcViewRegistry.current = this
  }

  override fun requestLayout() {
    super.requestLayout()
    post { RtcViewLayout.layoutChildren(this) }
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    RtcViewLayout.layoutChildren(this)
  }

  override fun onDetachedFromWindow() {
    if (RtcViewRegistry.current === this) {
      RtcViewRegistry.current = null
    }
    postDelayed(detachConfirm, DETACH_CONFIRM_MS)
    super.onDetachedFromWindow()
  }
}

object RtcViewRegistry {
  @Volatile
  var current: ViewGroup? = null
}
