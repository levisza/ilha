package app.ilha

import android.app.Application
import android.content.Context

class IlhaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as IlhaApp).graph
