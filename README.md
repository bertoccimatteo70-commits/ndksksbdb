# PTA — Pistoia Town Adventures

Open-world 3D per Android ambientato nella **provincia di Pistoia**, costruito su una
mappa geografica reale (OpenStreetMap + dati altimetrici), con due protagonisti,
campagna narrativa, veicoli guidabili, polizia, economia, case acquistabili con interni
visitabili, controlli touch personalizzabili e quattro preset grafici reali.

Motore: **libGDX 1.12.1 + renderer OpenGL ES 2.0 proprietario** (Java 17).
Nessun asset di terze parti, nessun marchio, nessuna musica protetta: texture, modelli,
personaggi, veicoli, musica ed effetti sono generati proceduralmente dal codice.

> **Release corrente:** [PTA 1.0.6 — installable update](https://github.com/bertoccimatteo70-commits/ndksksbdb/releases/tag/v1.0.6) · [Scarica PTA-1.0.6.apk](https://github.com/bertoccimatteo70-commits/ndksksbdb/releases/download/v1.0.6/PTA-1.0.6.apk) (8,5 MB)
>
> La Release 1.0.6 rende leggibili le strade in tutti i LOD, solidi gli edifici con
> collisione sul bordo reale dei poligoni OSM, sicuri gli ingressi/uscite degli interni
> e disegna il GPS sulla rete stradale. Tutti i 71.844 edifici, 26.296 strade,
> 17.679 elementi guidabili, aree, POI e 70 interni sono preservati.
> APK firmato con **una chiave di distribuzione stabile**, quindi installabile sopra le
> versioni precedenti (firma v2+v3, arm64-v8a, armeabi-v7a, x86_64).

---

## Stato del progetto

| Area | Stato |
|---|---|
| Mondo 3D continuo (Pistoia, Agliana, Montale, Quarrata, Casalguidi, Serravalle, Montecatini, Pescia) | implementato |
| Due protagonisti con cambio reale e storie personali | implementato |
| Campagna: 12 missioni principali + 2 secondarie, dialoghi italiani | implementato |
| Guida: 16 categorie di veicoli, carburante, danni, entrata/uscita | implementato |
| Polizia: stelle, inseguimenti, posti di blocco, nascondersi | implementato |
| Economia: missioni, negozi, concessionaria, tuning, proprietà, attività | implementato |
| Case con interni 3D visitabili, letto per salvare, garage | implementato |
| Controlli touch contestuali, ridimensionabili, spostabili, trasparenza, reset | implementato |
| Prima/terza persona a piedi e in auto | implementato |
| Preset BASE/MEDIA/ALTA/ULTRA + impostazioni avanzate + avviso prestazioni | implementato |
| Menu, mappa, missioni, salvataggi, crediti, intro cinematografica saltabile | implementato |
| Audio procedurale con volumi separati | implementato |
| APK installabile e pipeline GitHub Actions | build automatica nel workflow |

Le verifiche automatiche 1.0.6 sono **20 test JVM verdi**: caricamento della mappa
reale, conteggi OSM invariati, collisione sul bordo di un muro lungo, push-out da un
footprint, camminata e corsa contro una facciata, auto a 34 m/s contro un edificio,
percorso GPS che non attraversa footprint, strade presenti e contrastate in LOD 0/1/2,
interni, missioni, economia, preset e salvataggi. La build è stata installata e avviata
su emulatore Android x86_64: la città e le facciate sono state visualizzate in gioco
dopo il caricamento dei chunk, con HUD e spawn nella Piazza del Duomo. Il collaudo su
telefono fisico, la guida prolungata, la resa fotografica dell'asfalto in ogni zona e le
performance reali restano da fare: vedi [Limiti noti](#limiti-noti).

---

## Scheda Android e gestione RAM

### Requisiti

| Voce | Valore |
|---|---|
| Versione minima | Android 7.0 (API 24) |
| Target / compile SDK | Android 14 (API 34) |
| Architetture APK | arm64-v8a, armeabi-v7a, x86_64 |
| RAM consigliata | 3 GB, con preset MEDIA |
| GPU | OpenGL ES 2.0 |
| Connessione | non richiesta dopo l'installazione |
| Spazio | APK 8,5 MB; circa 7,7 MB di mappa copiati nello storage interno al primo avvio |

### Firma dell'APK

La Release `1.0.6` è un **APK release firmato** con la chiave di distribuzione
`keystore/pta-release.jks` (alias `pta`, validità fino al 2056). La chiave è nel
repository di proposito: senza un certificato stabile ogni runner GitHub Actions
genera un `debug.keystore` nuovo, e un APK firmato con una chiave diversa **non può
essere installato sopra** quello già presente sul telefono (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
È una chiave per la distribuzione diretta (sideload), non una chiave da store: chi
pubblica su Play Store deve sostituirla con una propria, passata tramite
`PTA_KEYSTORE_FILE`, `PTA_KEYSTORE_PASSWORD`, `PTA_KEY_ALIAS` e `PTA_KEY_PASSWORD`.

L'APK 1.0.5 e precedenti sono firmati con chiavi diverse fra loro: per passare a
1.0.6 bisogna **disinstallare una volta** la versione vecchia (i salvataggi interni
vanno persi, restano solo se si esporta prima il file). Dalla 1.0.6 in poi gli
aggiornamenti si installano sopra senza toccare i dati.

### Gestione della RAM

La mappa completa non viene caricata come 71.844 oggetti Java.

- Al primo avvio `pta_map.bin` viene copiato **integro** in `world/pta_map.bin` nello
  storage interno e mappato in sola lettura. Il file originale resta la fonte canonica:
  nessun edificio, strada, area o POI viene rimosso.
- In heap restano l'header, i 3.876 nomi, i 2.321 POI e gli offset/indici spaziali;
  sono metadati compatti. Le geometrie vengono decodificate solo quando un chunk
  entra nel raggio di streaming.
- Le cache hanno limiti espliciti: **16 chunk di edifici, 1.024 strade, 512 aree**.
  Quando un chunk viene scaricato, `releaseChunk()` libera la sua geometria.
- Con il preset MEDIA la distanza di streaming è **900 m**; i chunk oltre circa
  1.320 m vengono rimossi. LOD 1 e 2 disegnano **tutti** gli edifici come box
  semplificati: il livello di dettaglio non elimina elementi.
- I 70 interni sono **on-demand**: esiste una sola mesh attiva, creata all'ingresso e
  distrutta all'uscita.
- Per un dispositivo da 3 GB il default è **MEDIA (720p, 30 FPS, 900 m)**; il preset
  **BASE (480p, 620 m)** offre più margine. Non è stata eseguita una misura dell'heap
  su hardware reale: i limiti sono di progetto e sono coperti dai test JVM sulla cache,
  non da un benchmark su telefono.

---

## Controlli

### A piedi
- Stick virtuale sinistro: movimento.
- Area destra: trascina per ruotare la telecamera.
- `CORRI`, `SALTA`, `COLPO`, `USA` (interagisci), `SALI` (entra nel veicolo),
  `VISTA` (prima/terza persona), `CAMBIA` (protagonista), `II` (pausa).

### Alla guida
- `▲` acceleratore, `▼` freno/retromarcia, `◀`/`▶` sterzo, `FRENO` freno a mano,
  `SCENDI`, `VISTA`, `II`.

### Tastiera / controller
WASD o frecce, Shift, Spazio, R, E, F, V, C, Tab, Esc. Gamepad: stick sinistro,
A/B/X/Y, L1, L2, R2, Start.

Layout modificabile da **Impostazioni → Modifica layout**: trascina i pulsanti,
regola dimensione e trasparenza, ripristina i valori originali. Tutto viene salvato
nelle preferenze locali.

---

## Mappa

Dati reali, non inventati:

- **OpenStreetMap** (estratto Geofabrik `italy/centro`), licenza **ODbL**:
  strade, incroci, rotatorie, piazze, edifici, parchi, ferrovie, POI.
  Attribuzione in gioco (intro, crediti, HUD documentazione).
- **Mapzen / AWS Terrain Tiles** (public domain) per l'altimetria: le colline di
  Serravalle e Montale sono reali, non decorative.

Numeri della mappa cotta in `app/src/main/assets/map/pta_map.bin` (7,7 MB):

- 71.844 edifici
- 26.296 tracciati stradali (17.679 guidabili)
- 4.785 aree (parchi, piazzale, acqua, cimiteri…)
- 2.321 punti di interesse
- 3.876 nomi, 236 etichette di località

Formato binario compresso con: proiezione metrica locale (origine 43.9333 N, 10.9167 E),
altezze int16 su griglia 64 m, geometrie a 16 bit. Il file **completo** viene copiato
nello storage interno al primo avvio e mappato in sola lettura: `MapData` tiene in RAM
solo gli offset e l'indice dei chunk, non le 71.844 geometrie. La geometria viene
decodificata quando un chunk entra nel raggio di streaming (cache massima: 16 chunk di
edifici, 1.024 strade, 512 aree). La mappa resta continua e liberamente esplorabile;
nessun edificio, strada, area o POI viene eliminato dal file o dal mondo.

---

## Campagna

12 missioni principali in 5 capitoli, con obiettivi verificabili (raggiungi, guida,
entra nel veicolo, parla, raccogli prove, pedina, insegui, scappa, sopravvivi, gara),
dialoghi, ricompense e condizioni di fallimento. Due missioni secondarie (taxi e
consegne) restano disponibili dal menu Missioni.

Protagonisti:

- **Marco Bellandi** — meccanico, ex detenuto, officina di famiglia in centro.
- **Elisa Vannucci** — giornalista locale, casa a Casalguidi, indaga sulla Valdibure.

La missione finale sblocca l'esplorazione libera. Le case e le attività acquistate
restano dopo il finale.

---

## Impostazioni grafiche

I preset modificano davvero il motore:

| Preset | Risoluzione interna | FPS | Distanza | Traffico | Ombre | FXAA |
|---|---|---|---|---|---|---|
| BASE | 480p | 30 | 620 m | 0.55 | off | no |
| MEDIA | 720p | 30 | 900 m | 0.80 | basse | sì |
| ALTA | 1080p | 30 | 1300 m | 1.00 | alte | sì |
| ULTRA | 1080p | 60 | 1700 m | 1.00 | alte | sì |

Impostazioni avanzate: risoluzione, limite FPS, ombre, texture, distanza, traffico,
effetti, FXAA, FPS overlay, volumi separati (musica/effetti/ambiente/dialoghi),
dimensione e trasparenza dei controlli, layout mancino.

Se il frame rate medio scende sotto il 62% dell'obiettivo, il gioco mostra un avviso
con il preset inferiore consigliato e permette di applicarlo con un tocco.

---

## Salvataggi

- Autosave (slot 0, ogni 75 s e in pausa/chiusura).
- Tre slot manuali.
- Salvano: posizione, protagonista attivo, salute/armatura, denaro in contanti e banca,
  proprietà, veicoli posseduti, missioni completate, missione attiva, ora del giorno,
  tempo di gioco e impostazioni.
- File JSON locali in `saves/`, nessuna connessione richiesta.

---

## Struttura del progetto

```
core/                     motore e logica, testabile su JVM
  src/main/java/it/pta/
    world/                Geo, MapData (loader binario, collisioni, altimetria)
    render/               atlas procedurale, shader, chunk, streaming, FX
    entity/               protagonista, veicoli, traffico, polizia, telecamera
    game/                 World, PtaGame, missioni, economia, contenuti, salvataggi
    audio/                sintesi musicale e SFX in tempo reale
    ui/                   skin procedurale, HUD, touch, menu, intro
  src/test/java/          test JVM (mappa, missioni, economia, salvataggi, preset)
app/                      modulo Android
  src/main/java/it/pta/game/AndroidLauncher.java
  src/main/assets/        mappa, missioni, font, logo
  src/main/res/           icone launcher
tools/                    pipeline dati (OSM → mappa, DEM, icone)
.github/workflows/        build APK + test + artifact
```

Dettagli tecnici: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Compilare l'APK

Requisiti: JDK 17, Android SDK con platform 34 e build-tools 34.0.0.

```bash
export JAVA_HOME=/percorso/jdk-17
export ANDROID_HOME=/percorso/android-sdk
./gradlew :core:test
./gradlew :app:assembleDebug   # APK di collaudo, chiave debug locale
./gradlew :app:assembleRelease # APK firmato con la chiave di distribuzione
# APK: app/build/outputs/apk/release/app-release.apk
```

Il workflow `.github/workflows/android.yml` esegue test, compila l'APK release, ne
verifica firma, `versionCode` e librerie native, e lo pubblica come artifact
**PTA-apk** a ogni push su `main` (o manualmente da Actions →
“PTA Android build” → Run workflow). I log e i report dei test sono conservati come
artifact separato.

---

## Rigenerare la mappa (opzionale)

```bash
python3 -m pip install --target tools/pylibs pyosmium
python3 tools/osm/fetch_pbf.sh          # scarica l'estratto Geofabrik
python3 tools/map/fetch_dem.py          # tile altimetriche
PYTHONPATH=tools/pylibs python3 tools/map/build_map.py
python3 tools/branding/make_icons.py
```

---

## Limiti noti (dichiarati, non nascosti)

- **Nessuna ombra solare in tempo reale**: su GLES2 mobile il costo non è sostenibile.
  Sono implementate ombre di contatto, occlusione ambientale cotta e finestre
  emissive notturne.
- **Aerei ed elicotteri** usano fisica semplificata; **le barche** sono implementate ma
  nell'area spedita non c'è un corso d'acqua navigabile (l'Ombrone è troppo stretto):
  la categoria resta nel catalogo e nei modelli, non è utilizzabile in missione.
- **Nessun doppiaggio registrato**: i dialoghi sono testuali con voce sintetica
  (blip) e sottotitoli; i volumi dialoghi controllano quel canale.
- **Interni**: 70 edifici OSM selezionati sono visitabili. La mesh della stanza
  (dimensioni, colori e arredi variano in base all'edificio) viene generata solo
  all'ingresso e liberata all'uscita; non ci sono 70 interni contemporaneamente in RAM.
  Non sono ancora modellati interni architettonicamente unici per ogni singola casa.
- **Armi**: solo combattimento corpo a corpo; il cambio arma non è implementato.
- **Telefono** non implementato come schermata dedicata; le missioni si avviano dal
  menu Missioni e dalle offerte di trama.
- **IA del traffico e dei pedoni** semplificata (seguono la rete stradale reale, non
  fanno pathfinding globale); i bersagli delle missioni seguono waypoint.
- **Multipolygon OSM** complessi non importati; gli edifici sono estrusi dal footprint
  semplificato a 0,55 m.
- **Verifica su hardware**: test JVM, compilazione APK e avvio su emulatore Android
  x86_64 eseguiti. Il mondo è stato visualizzato nella Piazza del Duomo con spawn e
  camera liberi; touch prolungato, guida e performance su un telefono fisico restano
  da collaudare. Il primo avvio su telefono è il vero collaudo grafico e prestazionale.
- Il gioco è single-player, offline, senza account e senza acquisti in-app.

---

## Licenze e attribuzione

- Dati cartografici © **OpenStreetMap contributors**, licenza **ODbL**
  (<https://www.openstreetmap.org/copyright>). La banca dati derivata è distribuita
  con la stessa licenza; attribuzione presente in gioco.
- Altimetria: **Mapzen / AWS Terrain Tiles**, public domain.
- Codice, modelli, texture, personaggi, nomi di attività, musica ed effetti:
  originali del progetto PTA.
- Font: **DejaVu Sans** (licenza Bitstream Vera / DejaVu, redistribuzione consentita).
- Motore: **libGDX** (Apache 2.0).

Nessun contenuto di Grand Theft Auto, Rockstar Games o altre opere è riprodotto:
il riferimento al tono visivo PS3 è stilistico, non un riutilizzo di asset.
