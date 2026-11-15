package com.music.vivi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.ui.component.BottomSheetState

/**
 * TEMPORAL (diagnostico de la pantalla negra): escribe en pantalla el estado del reproductor.
 * Para quitarlo: borrar este archivo y la linea DiagnosticOverlay(state) al final de BottomSheetPlayer en Player.kt.
 */
@Composable
internal fun DiagnosticOverlay(state: BottomSheetState) {
    val pc = LocalPlayerConnection.current ?: return
    if (state.progress < 0.05f) return
    val playing by pc.isPlaying.collectAsState()
    val pbState by pc.playbackState.collectAsState()
    val meta by pc.mediaMetadata.collectAsState()
    val err by pc.error.collectAsState()
    val idx by pc.currentMediaItemIndex.collectAsState()
    val sheet = if (state.isExpanded) "abierto" else "moviendo"
    val pb = when (pbState) {
        1 -> "IDLE(1)"
        2 -> "BUFFERING(2)"
        3 -> "READY(3)"
        4 -> "ENDED(4)"
        else -> pbState.toString()
    }
    val text = "hoja: $sheet | suena: ${if (playing) "si" else "no"} | estado: $pb | cola: $idx\n" +
        "cancion: ${meta?.title ?: "SIN DATOS"} | caratula: ${if (meta?.thumbnailUrl != null) "si" else "no"}\n" +
        "letras abiertas: ${if (PlayerV2Backdrop.lyricsOpen) "si" else "no"} | error: ${err?.errorCodeName ?: "ninguno"}"
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        modifier = Modifier
            .padding(top = 48.dp, start = 6.dp)
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(4.dp)
    )
}
