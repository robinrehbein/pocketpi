# PocketPi – Handover (Stand 2026-10-14, ~17:35 UTC)

Repo: `robinrehbein/pocketpi`. Regeln stehen in `CLAUDE.md`; dazu:
- Robin auf Deutsch antworten; Code, Commits und PRs auf Englisch.
- Nur Merge-Commits.
- Ein Release läuft nur über `release.yml` (workflow_dispatch auf `main`), und zwar erst, wenn der CI-Push-Lauf für den aktuellen `main`-Commit grün ist.
- Nie den Production-Track und nie die alte App `de.joinnoah.pocketpi` ansprechen.

## Ziel
Alle Review-PRs umsetzen und mergen, dann ein finales Play-Release. Danach #88 und #89 mit einem eigenen kleinen Release.
Robin hat erlaubt, selbstständig zu mergen. Melden soll man sich nur bei Problemen, vor Releases und am Ende mit der Liste der Gerätechecks.

## Erledigt heute
- **Zwischen-Release:** release.yml lief erfolgreich auf `main` `bb1a1b0` (Lauf 38580101045) und ging auf die Play-Testing-Tracks. Es ist das erste Release mit R8.
- **Gemerged:**
  - Dependabot #87, #93, #94, #95, #96, #97
  - Lane-PRs #132 (B13), #133 (A16), #134 (B20)
  - #136 (B21) und #137 (B14), beide mit Fixes nach dem Merge von main, alle CI-Jobs grün
  - Fix #135 (Composer-Emulator-Test) und Dependabot #89 (wearable 20), beide von Robin gemerged
- **Lane A** (A1–A16) ist komplett gemerged.
- `main` steht auf `887d5b4` (Merge von #137). Die CI dafür lief bei der Übergabe noch; die letzte sicher grüne Push-CI war `6f9411e`.

## Offen / als Nächstes
1. Zuerst die `main`-CI auf `887d5b4` prüfen. Ist sie rot, das zuerst beheben.
2. **Lane B und Lane C** laufen noch als Workflows in der alten Session. In einer neuen Session laufen sie NICHT weiter.
   - Noch ohne Branch oder PR:
     - Lane B: B15 mac-hub, B16 list-empty-states, B17 theme, B18 onboarding-settings, B19 chat-actions, B24 chat-a11y, B25 copy-sweep.
     - Lane C: B22 compact-chrome, B23 markdown.
   - Zuerst prüfen, was inzwischen existiert: `git ls-remote origin 'refs/heads/claude/*'` und die Liste offener PRs.
   - Fehlende Items je Branch: umsetzen, unabhängig reviewen, Checks laufen lassen, als Draft-PR öffnen.
3. **Merge-Regel für Lane-PRs (verschärft):**
   - Erst mergen, wenn ALLE Jobs grün sind: Unit, Lint, Emulator, Tablet emulator, Minified smoke, Benchmark isolation.
   - Liegt der Branch weit hinter `main` oder hat er Konflikte: vorher `main` hineinmergen, kompilieren, pushen.
   - Kompilieren mit:
     ```
     source /opt/pocketpi-env.sh && ./gradlew --no-daemon -q -Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.workers.max=1 :testDebugUnitTest :compileDebugAndroidTestKotlin :wear:compileDebugKotlin
     ```
     Die Maschine hat 15 GB RAM, Gradle läuft sonst in einen OOM.
   - Bricht ein Job beim SDK- oder Image-Download ab („Premature EOF“, „Peer disconnected“), ist das Infrastruktur. Dann die gescheiterten Jobs einmal neu starten.
4. **Finales Release:** wenn alle Lane-PRs gemerged sind und die `main`-CI grün ist, `release.yml` auf `main` starten.
5. **Danach:** #88 (firebase-bom 35) aktualisieren, bei grüner CI mergen und ein kleines separates Release machen. #89 hat Robin schon gemerged; das Wearable-Update kommt also bereits mit dem finalen Release.
6. **Aufräumen:** Robin muss noch erlauben, dass alte Branches gelöscht werden, oder „Automatically delete head branches“ einschalten. Erst danach die gemergten Branches löschen.

## Gerätechecks für Robin (nicht selbst behaupten)
- **R8:** Pairing, Push + Antwort aus der Benachrichtigung, Widget, Share, App-Sperre, Artefakt öffnen, Übergabe an die Uhr, Play-Pre-Launch-Bericht.
- **Neue Funktionen:**
  - Einheitlicher Seitenkopf; Providers und Folders behalten den Chat-Kontext (#137)
- Composer: Bild vom Keyboard einfügen, Rich Paste, langes Einfügen als Datei (#136)
- Wearable-Lib 20 (#89): Übergabe an die Uhr erneut prüfen
- Command Palette und Session-Wechsel (Tab mit Tastatur)
  - Activity Board, angepinnte Sessions, Ungelesen-Marker
  - Medien- und Tool-Output-Cache, sauberer Wechsel zwischen Macs
  - Neues Anhang-Format (auch große Dateien)
  - Composer: ein Stop-Knopf, Sende-Modus, Vorschlags-Chip (Tab übernimmt)
  - Schnelleres Wiederverbinden

## Lane-Liste (Branch → Titel)
B14 claude/page-headers – One page header; Providers and Folders keep the chat in context
B15 claude/mac-hub – Mac hub: attention-first Projects page, cold start on the last Mac, project badges, pull-to-refresh
B16 claude/list-empty-states – Host cards, empty states, quick access and the notification card
B17 claude/theme – Palette and light/dark mode choices, theme picker, shape scale
B18 claude/onboarding-settings – Pairing and first-run flow, regrouped Settings with Devices and About, themed watch pairing
B19 claude/chat-actions – Chat actions, snackbars, session-controls copy and status shapes
B21 claude/composer-content – Composer on TextFieldState: keyboard image insertion, rich paste and long text as an attachment
B22 claude/compact-chrome – Compact chrome when vertical space is short, and a new-session sheet
B23 claude/markdown – Markdown, code blocks and bubbles: highlighting, lists, quotes, contrast, reading width
B24 claude/chat-a11y – Accessibility, RTL, split-screen sheet heights and one question-answer model
B25 claude/copy-sweep – Terminology and copy sweep (EN + DE)

Die genauen Spezifikationen pro Item stehen im ursprünglichen Review-Artefakt:
https://claude.ai/artifact/2SDQzjycdQ22cxMpYci2LG

## Offene Notiz für noah-monorepo (Host-Roadmap, am Ende an Robin)
- Sind die Pfade in `session.git` relativ zum Session-Ordner oder zum Repo-Root? (aus B10)
- `session.status` auch für andere Projekte liefern (aus B12)

## WIP-Snapshots (von den Lane-Agenten, Stand ~15:50 UTC)
- `wip/page-headers`: überholt, B14 ist als #137 gemerged. Löschen.
- `wip/composer-content`: überholt, B21 ist als #136 gemerged. Löschen.
Kann es sein, dass die Agenten der alten Session diese Branches inzwischen selbst als `claude/page-headers` bzw. `claude/composer-content` gepusht haben? Das zuerst prüfen; der neuere Stand gewinnt.
Dieses Handover liegt auch auf dem Branch `wip/handover` (HANDOVER.md).

## Lehre aus diesem Tag
Stacked Lane-Branches liegen oft 60 bis 100 Commits hinter `main`. Deshalb immer `main` hineinmergen, bevor die CI zählt. Danach sind schon mehrmals echte Fehler aufgetaucht (Layout-Kopf, Composer-Typerkennung). Die Testberichte stehen im Artefakt `pocketpi-emulator` bzw. `pocketpi-unit-tests` (TEST-*.xml). Der Fehler in `PushDeliverySetupTest` dort ist nur ein übersprungener Test (Assumption) und bedeutungslos.
