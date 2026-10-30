package pl.zuza.midea

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** One process-wide interpreter for foreground service and read-only UI. */
internal object PythonEngine {
    @Synchronized
    fun get(context:Context):Python {
        if(!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }
}
