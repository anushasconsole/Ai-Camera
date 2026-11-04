package com.aicamera.app

import android.app.Application
import com.google.firebase.FirebaseApp

class AICameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
