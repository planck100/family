package com.familytimemanager.app.lock

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.familytimemanager.app.DeviceRole
import com.familytimemanager.app.DeviceRoleStore
import com.familytimemanager.app.child.ChildDeviceStateStore
import com.familytimemanager.app.update.UpdateInstallAuthorization

class ChildAccessibilityService : AccessibilityService() {
    private lateinit var stateStore: ChildDeviceStateStore
    private lateinit var roleStore: DeviceRoleStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLabel: String by lazy {
        runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrDefault("Family Time Manager")
    }
    private val delayedRemovalGuard = Runnable { guardAgainstRemoval(null) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        stateStore = ChildDeviceStateStore(this)
        roleStore = DeviceRoleStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::stateStore.isInitialized) return

        if (stateStore.snapshot().locked) {
            openChildLockScreen()
        }

        guardAgainstRemoval(event)

        // MIUI can publish the window event before the shortcut menu/dialog nodes are ready.
        mainHandler.removeCallbacks(delayedRemovalGuard)
        mainHandler.postDelayed(delayedRemovalGuard, 120L)
    }

    private fun guardAgainstRemoval(event: AccessibilityEvent?) {
        // Protect every installation explicitly configured as a child device. Do not depend on a
        // remote device ID: it may be absent during local setup or after a configuration refresh.
        if (!::roleStore.isInitialized || roleStore.snapshot() != DeviceRole.CHILD) return

        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString()
            ?: root.packageName?.toString()
            ?: return
        if (pkg == packageName) return
        if (pkg in UNINSTALLER_PACKAGES && UpdateInstallAuthorization.isActive(this)) return

        val texts = collectTexts(root)
        val referencesThisApp = texts.any { it.contains(appLabel, ignoreCase = true) }
        val containsRemovalAction = texts.any { text ->
            REMOVAL_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
        }
        if (pkg == XIAOMI_LAUNCHER_PACKAGE) {
            // The shortcut menu contains our icon label elsewhere on the desktop plus a separate
            // "Remove" item, so matching the whole tree causes false positives. Xiaomi's actual
            // confirmation dialog combines the action and app name in the same text node, e.g.
            // "Remove Family Time Manager"; only that exact shape is blocked.
            val confirmationNamesThisApp = texts.any { text ->
                text.contains(appLabel, ignoreCase = true) &&
                    REMOVAL_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
            }
            if (confirmationNamesThisApp) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        val isInstallerForThisApp = pkg in UNINSTALLER_PACKAGES && referencesThisApp
        if (referencesThisApp && (containsRemovalAction || isInstallerForThisApp)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        out: MutableList<String> = mutableListOf(),
    ): List<String> {
        if (node == null || depth > MAX_NODE_DEPTH) return out
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (index in 0 until node.childCount) {
            collectTexts(node.getChild(index), depth + 1, out)
        }
        return out
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val MAX_NODE_DEPTH = 12
        private const val XIAOMI_LAUNCHER_PACKAGE = "com.miui.home"
        private val UNINSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
        )
        private val REMOVAL_KEYWORDS = listOf(
            "uninstall", "remove", "delete",
            "解除安裝", "解除安装", "卸載", "卸载", "移除", "刪除", "删除",
            "device admin", "device administrator", "裝置管理員", "设备管理员",
            "deactivate", "停用",
        )
    }
}
