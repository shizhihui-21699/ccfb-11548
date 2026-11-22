package com.acme.privacyshield

import android.app.Application
import com.acme.privacyshield.data.AppSettings

class PrivacyShieldApp : Application() {
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }
}
