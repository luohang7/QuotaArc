package dev.quotaarc.android

import android.app.Application

class QuotaArcApplication : Application() {
    internal val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppGraph.create(applicationContext)
    }
}
