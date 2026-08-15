package com.androidcompress.app

import android.app.Application
import android.content.Context
import com.androidcompress.app.di.AppContainer

class CompressApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

fun Context.container(): AppContainer =
    (applicationContext as CompressApplication).container
