package app.areada.ui.reader

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.areada.R
import app.areada.data.reader.ReaderLanguageMode
import app.areada.reader.tts.AndroidTtsController
import app.areada.reader.tts.ReaderTtsError
import java.util.Locale

@Composable
internal fun rememberAndroidTtsController(documentKey: String): AndroidTtsController {
    val context = LocalContext.current.applicationContext
    val controller = remember(documentKey) {
        AndroidTtsController(context)
    }
    DisposableEffect(controller) {
        onDispose(controller::release)
    }
    return controller
}

@Composable
internal fun ReaderTtsErrorEffect(error: ReaderTtsError?) {
    val context = LocalContext.current
    val message = when (error) {
        ReaderTtsError.NO_OFFLINE_VOICE -> stringResource(R.string.tts_no_offline_voice)
        ReaderTtsError.NO_READABLE_TEXT -> stringResource(R.string.tts_no_readable_text)
        ReaderTtsError.SYNTHESIS_FAILED -> stringResource(R.string.tts_synthesis_failed)
        ReaderTtsError.INITIALIZATION_FAILED -> stringResource(R.string.tts_initialization_failed)
        null -> ""
    }
    LaunchedEffect(error) {
        if (error != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

internal fun ReaderLanguageMode.toSpeechLocale(): Locale =
    localeTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
