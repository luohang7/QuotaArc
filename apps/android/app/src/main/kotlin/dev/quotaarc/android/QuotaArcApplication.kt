package dev.quotaarc.android

import android.app.Application

class QuotaArcApplication : Application() {
    internal val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppGraph.create(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // Graph construction launches authoritative credential restore and
        // reconciles widget scheduling only after that result is known.
        graph
    }
}
