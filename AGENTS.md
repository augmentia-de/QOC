# AGENTS.md – Projektverfassung (`qoc`)

> **Projekt-Pfad:** `/home/torsten/dev/my-projects/2oc/qoc`  
> **Company-OS Pfad:** `/home/torsten/dev/my-projects/company`

## 1. Oberste Direktiven (Vor JEDER Aufgabe ausführen)
1. **Unternehmens-Gehirn laden:** Lies zuerst die zentralen Leitplanken im Company-OS:
   * `/home/torsten/dev/my-projects/company/AGENTS.md`
   * `/home/torsten/dev/my-projects/company/brain/TASTE_PRINCIPLES.md`
   * `/home/torsten/dev/my-projects/company/brain/VOICE.md`
   * `/home/torsten/dev/my-projects/company/brain/memory/ANTI_PATTERNS.md`
2. **Projekt-Kontext laden:** Lies anschließend den lokalen Zustand dieses Java/Maven-Projekts:
   * `.ai/FACTS.md`
   * `.ai/ARCHITECTURE.md`
   * `.ai/memory/LESSONS_LEARNED.md` (falls vorhanden)
3. **Harte Gates respektieren:** Prüfe vor jeder kritischen Aktion `/home/torsten/dev/my-projects/company/fleet/gates/HARD_GATES.md`.
4. **Niemals Historie überschreiben:** Ergänze Log-, Memory- und ADR-Dateien immer chronologisch mit Zeitstempel (`YYYY-MM-DD HH:MM`).

## 2. Java / Maven / MCP Arbeitsregeln
* **Build & Tests:** Jede Code-Änderung muss über Maven (`mvn test` bzw. `./mvnw test`) kompilierbar und grün sein.
* **MCP-Tool-Hygiene:** Jedes MCP-Tool benötigt präzise, unmissverständliche Beschreibungen (gemäß `VOICE.md`) und strikte Input-Validierung.
* **Skills & Rollen nutzen:** Nutze die zentralen Rollen und Skills aus `/home/torsten/dev/my-projects/company/fleet/roles/` und `/home/torsten/dev/my-projects/company/skills/`.

## 3. Pflicht-Abschluss (Loop schließen)
Nach jedem abgeschlossenen Task in `qoc`:
1. Neue Projekt-Fakten oder geänderte MCP-Tools in `.ai/FACTS.md` / `.ai/ARCHITECTURE.md` nachtragen.
2. Wichtige Java/MCP-Erkenntnisse in `.ai/memory/LESSONS_LEARNED.md` (und projektübergreifende Lehren ins Company-OS) eintragen.
3. Kosten und Ergebnis im zentralen Ledger `/home/torsten/dev/my-projects/company/ops/cost_ledger.csv` (mit Projekt-Tag `qoc`) protokollieren.