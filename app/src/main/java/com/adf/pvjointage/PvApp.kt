package com.adf.pvjointage

import android.app.Application
import com.adf.pvjointage.data.Repository

class PvApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
    }
}
