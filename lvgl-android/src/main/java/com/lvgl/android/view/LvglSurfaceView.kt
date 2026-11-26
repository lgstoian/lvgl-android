// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.view

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import com.lvgl.android.accessibility.LvglNodeAccessibilityProvider
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.engine.LvglEngine
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SurfaceView that hosts the LVGL render surface.
 *
 * The LVGL engine is process-wide (see LvglEngine); this view only manages the
 * *display* bound to its surface. On surface recreation it re-initialises the
 * display without destroying the engine, so the current screen + theme survive.
 *
 * Ownership is token- and generation-scoped (@breadcrumb BC-001). Android can
 * deliver `surfaceDestroyed`/`onPause` for an outgoing activity *after* a newer
 * activity has bound its surface, so a callback is only allowed to unbind or
 * pause the engine while this view is still the active owner.
 */
open class LvglSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val engine = LvglEngine.instance
    private var nativeInitialized = false

    /**
     * Distinct token for this view. [LvglEngine] compares owner identity on
     * deinit, so a different view can never release our display (nor we theirs).
     */
    private val ownerToken = Any()

    /** Generation of the last successful bind; 0 means this view never bound. */
    private var boundGeneration = 0L

    /** Once retired, late surface callbacks from this view are ignored. */
    @Volatile
    private var retired = false

    /** Only LvglActivity hosts take part in the foreground-owner check. */
    private val hostActivity: LvglActivity? = findHostActivity(context)

    /** Mirrors registered Kotlin UI-builder nodes as virtual TalkBack views. */
    private val accessibilityProvider = LvglNodeAccessibilityProvider(context, this)

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        /* Surface sits behind window content so dialogs/permissions render on top.
         * setZOrderOnTop(true) is intentionally NOT used (it hides in-app
         * dialogs behind the LVGL surface and contributes to the ANR). */
        setZOrderMediaOverlay(false)
        setZOrderOnTop(false)
        /* Without these the host is skipped by accessibility services and the
         * node provider is never consulted. */
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityProvider.attach()
    }

    /** Virtual accessibility children of this surface; see [LvglNodeAccessibilityProvider]. */
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = accessibilityProvider

    /** Called when the surface is created; initialises the process-wide engine. */
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        /* A genuine recreation gives this view a fresh surface lifecycle. */
        retired = false
        engine.initialize()
    }

    /**
     * Binds the new surface to the shared LVGL display.
     *
     * A same-size surface is rebound rather than rebuilt, so the current screen
     * and theme survive; a size change rebuilds the display. A view whose host
     * activity has been superseded, or that a newer view has retired, must not
     * bind here: that is the stale-rebind half of the lifecycle race.
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (retired) {
            Log.d(TAG, "surfaceChanged ignored: retired view")
            return
        }
        val host = hostActivity
        if (host != null && !LvglActivity.isForeground(host)) {
            /* The outgoing activity's window can resize after the new activity
             * has bound (seen on real hardware). Rejecting this bind keeps the
             * newer owner — and its live screen — on the display. */
            Log.d(TAG, "surfaceChanged ignored: host is no longer foreground")
            retired = true
            return
        }
        /* A different view taking the display retires the previous one so its
         * late surfaceDestroyed/onPause cannot unbind the newer surface. */
        val previous = activeOwnerView.getAndSet(this)
        if (previous != null && previous !== this) {
            previous.retired = true
        }
        val generation = bindGeneration.incrementAndGet()
        boundGeneration = generation
        Log.d(TAG, "surfaceChanged: ${width}x${height} (gen=$generation)")
        val dpi = resources.displayMetrics.densityDpi
        /* The native side rebinds the surface to the shared display when the
         * size is unchanged, and only rebuilds on a genuine size change. */
        engine.initDisplay(ownerToken, holder.surface, width, height, dpi)
        nativeInitialized = true
        accessibilityProvider.refresh()
    }

    /** Unbinds the display when the surface goes away; the engine stays alive. */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        /* Prevent a late surfaceChanged from re-binding a destroyed surface. */
        retired = true
        if (isCurrentOwner() && activeOwnerView.compareAndSet(this, null)) {
            engine.deinitDisplay(ownerToken)
        } else {
            Log.d(TAG, "surfaceDestroyed ignored: stale owner")
        }
        nativeInitialized = false
    }

    /**
     * Forwards touch events to LVGL as actions 0 (down), 2 (move) and 1 (up).
     *
     * Called on the UI thread by the framework; always returns `true` so the
     * view keeps receiving the rest of the gesture. Touch forwarding is not
     * owner-scoped: the view receiving the gesture is the one displaying it.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> engine.onTouchEvent(0, x, y)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> engine.onTouchEvent(1, x, y)
            MotionEvent.ACTION_MOVE -> engine.onTouchEvent(2, x, y)
        }
        return true
    }

    /**
     * Resumes the LVGL engine when the owning activity resumes.
     *
     * Call from the activity's `onResume`; does nothing before the display has
     * been initialised. Resuming is harmless for a stale view (it only clears
     * `paused`), so it is always forwarded while the view is live.
     */
    fun onResume() {
        if (nativeInitialized) { engine.resume() }
        accessibilityProvider.refresh()
    }

    /**
     * Pauses the LVGL engine when the owning activity pauses.
     *
     * A stale activity must never set `paused` after a newer owner has bound;
     * only the current owner is forwarded to native.
     */
    fun onPause() {
        if (!isCurrentOwner()) {
            Log.d(TAG, "onPause ignored: stale owner")
            return
        }
        engine.pause()
    }

    /**
     * Unbinds this view's display from the engine.
     *
     * The engine itself is process-wide and intentionally not destroyed here —
     * the display is only unbound, so a later activity can rebind. Call when
     * the view is removed for good (for example in `onDestroy`).
     */
    fun destroy() {
        retired = true
        if (isCurrentOwner() && activeOwnerView.compareAndSet(this, null)) {
            engine.deinitDisplay(ownerToken)
        }
        nativeInitialized = false
        accessibilityProvider.detach()
        /* The engine itself is process-wide and intentionally not destroyed
         * here — the display is only unbound, so a later activity can rebind. */
    }

    /** True only while this view holds the most recent surface bind. */
    private fun isCurrentOwner(): Boolean =
        boundGeneration != 0L &&
            boundGeneration == bindGeneration.get() &&
            activeOwnerView.get() === this

    /** Holds the private log tag and the process-wide ownership bookkeeping. */
    companion object {
        private const val TAG = "LvglSurfaceView"

        /** Monotonic bind counter; a newer generation makes older views stale. */
        private val bindGeneration = AtomicLong(0)

        /** The view that most recently bound the shared display. */
        private val activeOwnerView = AtomicReference<LvglSurfaceView?>(null)

        /** Walks ContextWrappers so an LvglActivity host is found reliably. */
        private fun findHostActivity(context: Context): LvglActivity? {
            var current: Context? = context
            while (current != null) {
                if (current is LvglActivity) return current
                current = (current as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
