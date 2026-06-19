package com.familytimemanager.app.update

import android.content.Context

object UpdateInstallAuthorization {
    fun grant(context: Context) {
        prefs(context).edit()
            .putLong(KEY_ALLOWED_UNTIL, System.currentTimeMillis() + AUTHORIZATION_MILLIS)
            .apply()
    }

    fun isActive(context: Context): Boolean {
        return prefs(context).getLong(KEY_ALLOWED_UNTIL, 0L) > System.currentTimeMillis()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "app_update_install"
    private const val KEY_ALLOWED_UNTIL = "allowed_until"
    private const val AUTHORIZATION_MILLIS = 10 * 60 * 1000L
}
