package com.example.streamapp

import android.app.Application

class MainApplication : Application() {
    lateinit var authRepository: AuthRepository
        private set
    lateinit var appSessionViewModel: AppSessionViewModel
        private set

    override fun onCreate() {
        super.onCreate()
        authRepository = AuthRepository(this)
        appSessionViewModel = AppSessionViewModel(this, authRepository)
    }
}
