package com.adskip.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogStore.log("APP", "启动")
    }
}
