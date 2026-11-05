package com.lume.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()
    private var backLongHandled = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vm.reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A launcher must never "finish" on Back; Back just closes overlays / returns to the first tile.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                vm.goHome()
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        setContent { LumeApp(vm) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshRecents()
    }

    /** Home pressed while Lume is already showing: close overlays and jump back to the first tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.goHome()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        when (e.keyCode) {
            // Hold Back = All apps.
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount >= 1) {
                    if (!backLongHandled) {
                        backLongHandled = true
                        if (vm.overlays.isEmpty()) vm.show(Overlay.Drawer)
                    }
                    return true
                }
                if (e.action == KeyEvent.ACTION_UP && backLongHandled) {
                    backLongHandled = false
                    return true
                }
            }
            // Keys that some remotes have: Menu / Settings = quick settings, Search = search, Input = inputs.
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) vm.show(Overlay.Quick)
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) vm.show(Overlay.Search)
                return true
            }
            KeyEvent.KEYCODE_TV_INPUT -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) vm.show(Overlay.Inputs)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
