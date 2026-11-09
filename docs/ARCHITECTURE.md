# PTA — Architettura tecnica

## Perché libGDX + renderer custom

Requisiti: APK Android reale, mondo 3D continuo, nessun browser/WebView, nessuna
dipendenza da servizi online. libGDX 1.12.1 offre il ciclo di vita Android, input,
audio e file system; il rendering è un renderer OpenGL ES 2.0 scritto nel progetto
(`it.pta.render`) per controllare budget di memoria, LOD e draw call.

Java 17 (non Kotlin) per: build Gradle semplice, test JVM immediati, nessuna
toolchain aggiuntiva in CI.

## Flusso dati della mappa

```
Geofabrik italy/centro (387 MB PBF)
  → tools/map/build_map.py (pyosmium, shapely)
    → pta_map.bin  (magic PTAMAP01, versione 2)
    → pta_map.json (statistiche)
    → landmarks.json (comuni + luoghi noti, coordinate reali)
```

Il file binario contiene: header e proiezione, griglia DEM int16, tabella nomi,
edifici (footprint + altezza + colori), strade (classe, larghezza, flag, punti),
aree, POI. Il loader (`MapData`) costruisce un indice CSR per chunk da 512 m:
`buildingsIn`, `roadsIn`, `areasIn`, `poisIn` restituiscono indici senza allocare.

Collisioni: `blocked(x,z,radius)` contro i footprint; `resolveCollision` per il
personaggio; `heightAt` bilineare sul DEM; `nearestRoad` su bbox con filtro guidabile.

## Rendering

- `ChunkManager`: streaming asincrono su thread di background, LOD 0/1/2 per distanza,
  upload GPU sul thread GL, eviction oltre la distanza di vista.
- `ChunkBuilder`: terreni, strade con marciapiedi e segnaletica, edifici estrusi con
  tetti, alberi, lampioni, acqua. Un chunk è **una draw call** grazie a un atlante 3×3
  e all'indice del tile codificato nell'alpha del colore vertice.
- Indici a 16 bit: split a 60.000 vertici per chunk.
- `WorldRenderer`: cielo procedurale, nebbia, FBO a risoluzione scalata (preset),
  post-processing (FXAA, vignette, saturazione, pioggia).
- `FxRenderer`: glow additivi, beacon missione, ombre blob, fari notturni.
- Finestre emissive notturne tramite canale alpha dell'atlante facciata.

Nessuna shadow map in tempo reale: scelta documentata per GLES2 e thermal budget.

## Entità

- `Humanoid`: 6 mesh (torso, testa, braccia, gambe), animazione procedurale
  camminata/corsa/salto/pugno; palette per personaggio.
- `Player`: fisica a piedi, stamina, collisioni, mischia, modalità interni.
- `VehicleType`: 16 categorie con prestazioni, carburante, prezzo, posti.
- `Vehicle`: fisica arcade con aderenza per superficie, danni, carburante, pitch/roll
  sul terreno, luci, modello procedurale e ruote animate.
- `TrafficSystem`: auto e pedoni sulla rete reale, spawn a distanza, separazione,
  reazioni a collisioni e clacson.
- `PoliceSystem`: stelle, inseguimento, ufficiali a piedi, posti di blocco,
  raffreddamento del ricercato quando non visti, arresto.
- `CameraRig`: terza persona orbitale con collisione, prima persona, chase vehicle.

## Gioco

- `World`: orchestrazione di protagonisti, veicoli posseduti, missioni, polizia,
  economia, meteo, interni, audio e salvataggi. Espone `Listener` verso la UI.
- `MissionSystem`: carica `missions.json`; obiettivi tipizzati (`goto`, `drive_to`,
  `enter_vehicle`, `talk`, `collect`, `photo`, `chase`, `tail`, `escape`, `survive`,
  `race`), marker, bersagli con waypoint, timer, fallimenti, dialoghi e ricompense.
- `Economy`: contanti, banca, ledger, proprietà, attività con reddito, veicoli.
- `SaveManager`: 4 slot JSON (autosave + 3 manuali), nessuna rete.
- `Content`: luoghi di storia, case, negozi, distributori dalle coordinate OSM reali;
  nomi di attività inventati.
- `AudioEngine`: sintesi a 22.050 Hz su thread dedicato; 5 loop musicali originali
  (menu, esplorazione, inseguimento, missione, notte), motore, sirena, pioggia,
  passi, collisioni, UI, blip dialoghi; volumi separati.
- `PtaGame`: state machine INTRO → MENU → PLAY, camera cinematografica, offerta
  missioni, autosave, lifecycle Android, monitor prestazioni con suggerimento preset.
- `GameUi` / `Hud` / `TouchControls` / `IntroCinematic`: Scene2D costruito via codice,
  font FreeType, skin procedurale; HUD con minimappa reale (strade vicine), stelle,
  soldi, salute, carburante, obiettivi e dialoghi.

## Interni

All'ingresso di una casa o garage viene costruita/caricata una mesh locale (pavimento,
pareti, soffitto, arredi) e il giocatore passa in modalità `indoor`: collisioni sui
limiti della stanza, nessun rendering del mondo esterno, camera ravvicinata. Il letto
salva; il ponte del garage richiama un veicolo posseduto. È una singola planimetria
per tipo, non interni unici per edificio (limite dichiarato).

## Test

`core/src/test/java` esegue su JVM senza Android:

- caricamento del binario mappa, strade reali al Duomo, altimetria di Serravalle;
- validazione di tutte le missioni (id, titolo, ricompensa, obiettivi, dialoghi);
- copertura delle località richieste nei landmark;
- economia (spese, acquisti, reddito), preset grafici, round-trip salvataggi.

## Estensioni previste

- Nuove zone: rigenerare il binario con un bbox più ampio; il loader/streaming non
  cambia. `Content` va esteso con i nuovi luoghi.
- Nuovi veicoli: aggiungere una costante a `VehicleType` e, se serve, una variante in
  `VehicleModel`; il catalogo, il traffico e il concessionario la leggono
  automaticamente.
- Nuove missioni: aggiungere un oggetto a `missions.json`; il motore interpreta i tipi
  già supportati.
- Nuovi interni: sostituire la mesh in `World.room(...)` con modelli per proprietà.
- Ombre reali: predisporre una shadow map solo su preset ULTRA se il target lo regge,
  mantenendo il fallback blob.
