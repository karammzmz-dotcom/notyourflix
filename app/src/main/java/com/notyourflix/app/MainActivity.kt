package com.notyourflix.app

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: Vm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(vm) }
    }

    // Pressing Home while a video plays shrinks it into a picture-in-picture window
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.screen is Screen.Player) enterPip(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    // Game controllers: A = select (D-pad center), B = back
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val mapped = when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> null
        }
        if (mapped != null) {
            return super.dispatchKeyEvent(
                KeyEvent(e.downTime, e.eventTime, e.action, mapped, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
            )
        }
        return super.dispatchKeyEvent(e)
    }
}
