package com.filewall.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.filewall.wear.data.WatchRepository
import com.filewall.wear.ui.WearApp

class MainActivity : ComponentActivity() {

    private val repository by lazy { WatchRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        setContent { WearApp(repository) }
    }

    override fun onStart() {
        super.onStart()
        // The listener only matters while something is on screen to redraw.
        repository.start()
    }

    override fun onStop() {
        repository.stop()
        super.onStop()
    }
}
