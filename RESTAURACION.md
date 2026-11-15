# RESTAURACION.md — Memoria del arreglo de la pantalla negra

> Leer junto con `claude.md`. Actualizar al terminar cada entrega.

## Problema
Al reproducir cualquier canción (Descargas o en línea) la pantalla se queda en **negro**. Sigue negra aunque se cierre y abra la app. En **Vivi Music no pasa**. Aún no se sabe si la canción suena con la pantalla negra.

## Reglas de esta restauración
- Ella NO compila hasta el final. Entregar ZIP por ZIP (un paso cada uno) y seguir al siguiente sin esperar prueba.
- Vivi (`vivi-music-main.zip`, v7.0.1 / code 80) es la referencia. Gracie es v7.0.0 / code 79.
- NO copiar archivos de Vivi enteros: usan cosas que Gracie eliminó (Listen Together, Spotify/Canvas, Motion Artwork, Discord, Last.fm, Shazam, módulos kizzy/lastfm/spotify...). Se **traslada la lógica de Vivi** a los archivos de Gracie, sin tocar lo de abajo.
- Cuenta de lo que ya falló: v01, v02, v03 no arreglaron el negro (compiladas por ella).

## Cambios de Gracie que se DEBEN conservar
Música local (`localmedia/`, `LocalMusicScreen`), letras (LastLyricsCache, offset 100 ms, keep-controls, Bini por duración, proveedores sin Spotify), LastMetadataCache, restauración del reproductor abierto en MainActivity, BlurWanderDrift (el efecto, si no es la causa), FreezeWatchdog, sistema de ZIPs, módulos eliminados siguen eliminados.

## Sospechoso principal (por lectura de código, NO probado)
`ui/player/Player.kt` (~líneas 783-860): el fondo difuminado de Gracie usa un cuadrado mucho más grande que la pantalla (`blurBackdropFootprint`, de `BlurWanderDrift.kt`) con `.blur(64.dp)` + `CompositingStrategy.Offscreen` + degradado negro encima. Vivi usa `fillMaxSize()` + `.blur(120.dp)` + capa negra 0.30. Un buffer gigante puede dibujar negro en algunos teléfonos.

## Pasos
- [x] v01 `BottomSheet.kt`: punto medio como Vivi (conservar).
- [x] v02/v03: pruebas que no arreglaron. **Revertidas en v05** (se vuelven a los originales de Gracie).
- [x] v04: este archivo. v05: revertir `MainActivity.kt` y `PlayerConnection.kt`.
- [ ] v06 **Fondo del player**: en `Player.kt` usar el fondo de Vivi (blur simple) en lugar del cuadrado grande. Dejar `BlurWanderDrift.kt` sin usar si hace falta.
- [ ] v07 **Player_v2 / Thumbnail / PlayerV2Landscape**: comparar con Vivi y trasladar su lógica de dibujo, quitando lo eliminado.
- [ ] v08 **MiniPlayer / Queue_v2 / Lyrics_v2**: igual (cuidando LastLyricsCache).
- [ ] v09 **MainActivity**: zona del reproductor (hoja, mini player) alineada con Vivi, conservando la restauración.
- [ ] v10 **PlayerConnection / MusicService**: alinear con Vivi lo de reproducción, conservando música local y LastMetadataCache.
- [ ] Al final: ella compila. Si sigue negro: pedirle (1) si suena la canción, (2) entrega de diagnóstico que muestre el error en pantalla.

## Notas
- Al terminar cada paso: marcar [x] aquí y entregarlo en el ZIP.
