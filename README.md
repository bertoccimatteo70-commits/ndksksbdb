# PTA — Pistoia Town Adventures

Open-world 3D per Android ambientato nella **provincia di Pistoia**, costruito su una
mappa geografica reale (OpenStreetMap + dati altimetrici), con due protagonisti,
campagna narrativa, veicoli guidabili, polizia, economia, case acquistabili con interni
visitabili, controlli touch personalizzabili e quattro preset grafici reali.

Motore: **libGDX 1.12.1 + renderer OpenGL ES 2.0 proprietario** (Java 17).
Nessun asset di terze parti, nessun marchio, nessuna musica protetta: texture, modelli,
personaggi, veicoli, musica ed effetti sono generati proceduralmente dal codice.

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

Le verifiche automatiche coprono: caricamento della mappa reale, presenza di strade e
altimetria, contenuto delle missioni, economia, preset e salvataggi. La resa grafica e
il touch **non sono stati provati su un dispositivo fisico o emulatore** in questo
ambiente (nessun display/GPU Android): vedi [Limiti noti](#limiti-noti).

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
indice CSR per chunk da 512 m, altezze int16 su griglia 64 m, geometrie a 16 bit.
La mappa è continua e liberamente esplorabile; il streaming carica solo i chunk vicini.

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
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Il workflow `.github/workflows/android.yml` esegue test, compila l'APK e lo pubblica
come artifact **PTA-debug-apk** a ogni push su `main` (o manualmente da Actions →
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
- **Interni**: una stanza 3D reale per case e una per garage, condivisa da tutte le
  proprietà (arredi e planimetria identici, posizione diversa). Non sono modellati
  interni unici per ogni abitazione.
- **Armi**: solo combattimento corpo a corpo; il cambio arma non è implementato.
- **Telefono** non implementato come schermata dedicata; le missioni si avviano dal
  menu Missioni e dalle offerte di trama.
- **IA del traffico e dei pedoni** semplificata (seguono la rete stradale reale, non
  fanno pathfinding globale); i bersagli delle missioni seguono waypoint.
- **Multipolygon OSM** complessi non importati; gli edifici sono estrusi dal footprint
  semplificato a 0,55 m.
- **Verifica su hardware**: test JVM e compilazione APK eseguiti; rendering, touch e
  performance non sono stati osservati su un dispositivo in questo ambiente. Il primo
  avvio su telefono è il vero collaudo grafico.
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
