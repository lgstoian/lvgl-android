// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.ui

/**
 * Events emitted by app-defined LVGL nodes.
 *
 * App screens are declared in Kotlin with [LvglUiScope] and rendered by the
 * native UI kit; interactions come back as [LvglUiEvent]s through
 * [LvglUi.listener]. Keeping the events typed means an app never has to know
 * about the JSON wire format or the native event codes.
 *
 * @breadcrumb BC-033
 */
sealed class LvglUiEvent {
    /** The node that produced the event. */
    abstract val nodeId: String

    /** A button was tapped. */
    data class Click(override val nodeId: String) : LvglUiEvent()

    /**
     * A slider value changed. [value] is the new value in the node's range.
     *
     * @property nodeId the slider that changed.
     * @property value the new value.
     */
    data class ValueChanged(override val nodeId: String, val value: Int) : LvglUiEvent()

    /**
     * A switch or checkbox changed state.
     *
     * @property nodeId the control that changed.
     * @property checked whether the control is now on.
     */
    data class Toggled(override val nodeId: String, val checked: Boolean) : LvglUiEvent()

    /**
     * A dropdown selection changed.
     *
     * @property nodeId the dropdown that changed.
     * @property index the newly selected option.
     */
    data class Selected(override val nodeId: String, val index: Int) : LvglUiEvent()
}

/**
 * Receives events from app-defined LVGL screens.
 *
 * Registered with [LvglUi.listener]; the screen id is part of every event.
 */
fun interface LvglUiListener {
    /**
     * Called for every interaction on the registered screen.
     *
     * @param screenId the app screen that produced the event.
     * @param event the typed interaction.
     */
    fun onUiEvent(screenId: Int, event: LvglUiEvent)
}

/**
 * Primitive event sink the native layer calls through JNI.
 *
 * This is the wire boundary: the native UI kit only knows a node id, an event
 * type string and an integer value. [LvglUi] converts these into [LvglUiEvent]s.
 */
fun interface LvglUiEventReceiver {
    /**
     * Called by the native layer when a node is interacted with.
     *
     * @param screenId the app screen that owns the node.
     * @param nodeId the id assigned by `LvglUiScope`.
     * @param type one of `click`, `value` or `toggle`.
     * @param value the value carried by the event.
     */
    fun onUiEvent(screenId: Int, nodeId: String, type: String, value: Int)
}
