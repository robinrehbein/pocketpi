package de.joinnoah.pi.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal class NavigationTestActivity : ComponentActivity() {
    var notification by mutableStateOf<RemoteNotification?>(null)
    var flush: suspend () -> Unit = {}
    var clearViewModelsOnDestroy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val content = contentFactory(this)
        setContent(content = content)
    }

    override fun onStop() {
        lifecycleScope.launch { flush() }
        super.onStop()
    }

    override fun onDestroy() {
        if (clearViewModelsOnDestroy) viewModelStore.clear()
        super.onDestroy()
    }

    companion object {
        var contentFactory: (NavigationTestActivity) -> (@Composable () -> Unit) = { {} }
    }
}
