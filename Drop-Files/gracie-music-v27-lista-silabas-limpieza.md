# Lista — gracie-music: iluminar sílaba por sílaba

## Lo que ella quiere
- Canciones con tiempo por palabra se iluminan sílaba por sílaba, con cortes que suenen bien.
- Que el código sea universal (reglas que sirvan para muchas palabras), no una lista infinita de palabras sueltas.

## Hecho
- `LyricsSyllables.kt`: reglas EN/ES, reparto de tiempo, "ing" con consonante sola (no-thing, be-co-ming), lista `EXCEPTIONS`.
- `gracie-music-v11-fix-silabas.zip`, `v13-fix-silabas-raya.zip` (raya "—": la palabra siguiente baja de línea), `v14-fix-silabas-todas.zip` (sílabas de antes normales, la última se lleva el resto, en todas las palabras partidas).
- Con v14 "rumble" ya se ve bien (ella lo confirmó).
- `gracie-music-v15-fix-silabas-tiempo.zip`: tope suave en vez del corte brusco de v14: cada sílaba de antes dura como mucho 0,12 s por letra y la última se lleva el resto. Palabras normales vuelven al reparto por tamaño (como antes de v11); "rumble" largo sigue sosteniendo el final.
- `gracie-music-v16-fix-letra-secundaria.zip`: al abrir la letra o al saltar, la línea elegida era la de mayor número aunque fuera de fondo; ahora se prefiere la principal (`Lyrics_v2.kt`, 2 sitios). El desplazamiento normal ya lo hacía bien.
- `gracie-music-v17-fix-silabas-sufijos.zip`: regla general de terminaciones (-less, -ness, -ment, -ful, -ache): time-less, dark-ness, care-ful, heart-ache, pay-ment, sin lista de palabras.
- `gracie-music-v18-fix-taylors-version.zip`: nuevo `TitleVersionTag.kt` quita "(Taylor's Version)" y "(From The Vault)" del título; LRC red lo usa al buscar y al comparar, Bini reintenta sin la etiqueta si falla.
- `gracie-music-v19-feat-widget-puntos.zip`: widget de notificación sin los puntitos de página de abajo (`widget_notification.xml`); botones de 36 a 44 dp de alto.
- `gracie-music-v20-fix-franja-brillo.zip`: franja de brillo 0,6 en vez de 1, SOLO estilo Apple (`Apple.kt`). Ella usa Expressive, que ya tenía 0,4, así que a ella no le cambia nada.
- `gracie-music-v21-fix-palabra-golpe.zip`: palabras de una sola sílaba (class, buy, don't) se prenden de golpe, estilo Apple. Lo que veía en "class" (Sabrina Carpenter, Diamonds Are Forever) no era un corte de sílabas: era el brillo recorriendo la palabra.
- `gracie-music-v22-fix-palabra-golpe-expressive.zip`: lo mismo en el estilo Expressive (el que ella usa), en `Expressive.kt`. Pendiente que pruebe.
- `gracie-music-v23-fix-letra-interruptor.zip`: al cambiar el interruptor de sílabas con la misma canción, la letra quedaba en blanco hasta cambiar de canción. Causa: `Lyrics_v2.kt`, el efecto que muestra la letra tras medirla solo se repetía si cambiaba el número de líneas (`mergedLyricsList.size`); ahora se repite al cambiar la lista. Pendiente que pruebe.

- `gracie-music-v24-fix-silabas-prefijos.zip`: regla general de prefijos en `LyricsSyllables.kt`: with-out, with-in, with-draw, de-stroy, de-scribe (antes wi-thout, des-troy). Probé ~80 palabras con una copia de las reglas en Python: el resto ya salía bien. Pendiente que pruebe.
- `gracie-music-v25-fix-silabas-compuestas.zip`: 4 reglas generales en `LyricsSyllables.kt`: compuestas (any-one, when-ev-er, wher-ev-er, fire-fly, fire-works, some-what), "w" tras vocal (show-ing, draw-ing, know-ing, pow-er, flow-er, new-er), "igh/ight" (high-er, bright-er, fight-er) y ev-er / nev-er. Probado con copia en Python (~150 palabras, EN y ES); las palabras de v24 no cambian salvo never y anything. Pendiente que pruebe.
- `gracie-music-v26-fix-silabas-vocales.zip`: vocales juntas que suenan separadas, en `LyricsSyllables.kt` (`isHiatus`): i-de-a, a-re-a, po-et, cru-el, qui-et, ra-di-o, vi-de-o, li-on, cha-os, ac-tu-al, di-a-mond; no parte nation, special, million, people. Prefijos cre-ate, re-act, sci-ence. Probado en copia Python (~100 palabras). Pendiente que pruebe.
- `gracie-music-v27-fix-silabas-limpieza.zip`: `EXCEPTIONS` recortada a 4 palabras sin regla (ever, never, every, archangelo). Nueva regla "-ly" tras "e" muda (love-ly, lone-ly, like-ly, en-tire-ly) y compuestas con life-, for-, no- (life-time, life-long, for-ev-er, no-where). Probado en Python (~80 palabras, sin cambios en las de antes). Cambio pequeño: nobody ahora no-bo-dy (antes no-bod-y). Pendiente que pruebe.

## Pendiente (en este orden, una parte por entrega)
1. Palabras muy rápidas (mo-ney) que se iluminan tarde. v20 no le sirve (es del estilo Apple y ella usa Expressive, que ya tiene franja 0,4). Causa sin confirmar; hay que ver cómo se ve ahora en Expressive y, si sigue tarde, mirar el tiempo de la sílaba, no la franja.
2. (Hecho) Interruptor de sílabas: ella confirmó que funciona (solo apaga las sílabas que pone la app). Su fallo de la letra en blanco al apagarlo se arregló en v23 (pendiente que pruebe).
3. (Hecho en v16, pendiente que pruebe) Letra secundaria (de fondo): si está en la línea siguiente y va por delante de la principal, la pantalla pone esa como activa. Debe quedarse en la principal. (Suposición mía; si falla, que mande captura.)
4. (Parcial en v17 y v22) Cortes que fallan. "class": era el brillo recorriendo la palabra, no un corte; arreglado en v22 para Expressive (pendiente que pruebe). Faltan di-sas-ter, heart-ache, pro-vo-ca-tive, time-less: las reglas ya los parten bien; si en pantalla salen mal, el corte viene del proveedor. Falta que ella diga de qué canción y proveedor.
5. (Hecho en v18, pendiente que pruebe) Proveedores LRC red y Bini: no encuentran letras de Taylor Swift cuando el título trae "(Taylor's Version)". Quitar esa etiqueta al buscar (y probar también sin ella como segundo intento). Mirar `LrcRedLyricsProvider.kt` (`cleanVideoTitle`, `normalize`, regex de la línea ~86 que quita feat/remaster pero no "Taylor's Version") y `BiniLyricsProvider.kt`.
6. (HECHO en v24 a v27, pendiente que pruebe) Reglas más universales. Solo queda, si ella manda canciones/palabras que fallen, añadir regla o excepción. Sin resolver: real/really (se deja real-ly), withering (withe-ring). AL FINAL, tarea grande (puede gastar muchos tokens; por partes): hacer el código más universal. Armar ~150 palabras de prueba (con las que ya fallaron: life-time, no-thing, be-co-ming, snakes, rum-ble, call-ing, di-sas-ter, heart-ache, pro-vo-ca-tive, time-less, class, eternity, saeculorum, satanás, mo-ney), mejorar las reglas (compuestas, prefijos dis-/pro-, sufijos -less/-ache/-tive, vocales juntas, "e" muda) y dejar en excepciones solo lo que no tenga regla.
7. Opcional (no pedido): canciones con tiempo solo por línea.

## Para retomar
- Pendiente 1 (mo-ney tarde): revisé la sílaba activa (`rememberActiveSyllableIdxState`) y es exacta, sin retraso. Sospecha sin confirmar: el brillo suave (0,4 de la letra) hace que una sílaba corta se vea completa algo después de empezar. Hace falta que ella diga si sigue pasando en v22/v23.
- Archivos: `app/src/main/kotlin/com/music/vivi/lyrics/LyricsSyllables.kt` (se llama desde `LyricsUtils.parseLyrics`); pantalla en `ui/component/lyrics/words/` (Apple.kt, WordAnimationUtils.kt); `WordLevelLyrics.kt` (salto tras la raya).
- ELLA USA EL ESTILO EXPRESSIVE. Todo cambio de pantalla de letras debe aplicarse en `Expressive.kt` (y en Apple si conviene), no solo en Apple.
- Palabra de golpe: `VisualCluster.isWholeWord` (`WordAnimationUtils.kt`), `computeWipe(..., instantWholeWord)` (`LyricsGlyphMaskCache.kt`), activado en `Apple.kt` (3 sitios) y `Expressive.kt` (1 sitio). Para quitarlo: `instantWholeWord = false`.
- Widget: `res/layout/widget_notification.xml`; `ic_widget_n_dots` quedó sin usar.
- Excepciones (`EXCEPTIONS`): solo ever, never, every, archangelo. Reglas nuevas en `LyricsSyllables.kt`: prefijos (with-, de-str, cre-ate, re-act, scien-), compuestas (`COMPOUND_HEADS/TAILS`), "-ly", "w" tras vocal, "igh", `isHiatus` (vocales juntas). Para probar reglas sin app: copia en Python, mismo comportamiento.
- Constantes de tiempo: `MAX_SECONDS_PER_LETTER` 0.12 (tope por letra de las sílabas de antes).
- No hay compilador en el chat: todo revisado a mano, sin probar en canción real.
