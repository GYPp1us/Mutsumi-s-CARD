package com.mutsumi.card.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import android.util.Log
import com.mutsumi.card.BuildConfig

class RecoverableUserException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

@Stable
class FeedbackController(
    val hostState: SnackbarHostState = SnackbarHostState(),
) {
    suspend fun show(message: String) {
        hostState.showSnackbar(message)
    }

    suspend fun report(error: Throwable, fallbackMessage: String) {
        Log.e(LOG_TAG, fallbackMessage, error)
        if (BuildConfig.DEBUG && error !is RecoverableUserException) {
            throw error
        }
        hostState.showSnackbar(error.message?.takeIf { it.isNotBlank() } ?: fallbackMessage)
    }

    private companion object {
        const val LOG_TAG = "MutsumiCard"
    }
}

@Composable
fun FeedbackHost(controller: FeedbackController) {
    SnackbarHost(hostState = controller.hostState)
}
