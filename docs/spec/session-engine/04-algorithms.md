# 04 – Algorithms

> Pseudocode in the neutral notation from [`README.md`](./README.md).
> Every procedure: **precondition**, **postcondition**, **error cases**.
> Errors are passed as values (`fail(error)`), interruption is its own
> channel.

---

## A. Admission

**Precondition:** Session exists.
**Postcondition:** Exactly one inbox row present **or** it is recognized that the ID
was already admitted. No model call.
**Error cases:** `SitzungNichtGefunden`, `Eingabekonflikt`, `AnhangFehler`,
`FaehigkeitNichtGefunden`.

```
aufnahme(anfrage) =
  sitzung ← lade_sitzung(anfrage.sitzungsID)      # fail(SitzungNichtGefunden)

  # 1. Idempotency first: an already known ID wins.
  vorhanden ← finde_inbox(anfrage.id)
  if vorhanden existiert:
      if vorhanden.typ ≠ anfrage.typ oder vorhanden.sitzungID ≠ sitzung.id:
          fail(Eingabekonflikt)
      return { aufgenommen: vorhanden, neu: nein }

  # 2. Preparation: hooks, attachments, capabilities.
  maskierend {
      wiederherstellbar {
          vorbereitet ← bereite_vor(sitzung, anfrage)   # fail(AnhangFehler, FaehigkeitNichtGefunden)
      }

      # 3. An open revert is committed before new work.
      if sitzung.revert vorhanden:
          schreibe_revert(sitzung)

      # 4. Admission: a durable event, from which the row arises.
      aufgenommen ← veroeffentliche(InboxEnqueued {
          sitzungsID: sitzung.id, inboxID: anfrage.id, element: vorbereitet.element
      })
  }

  # 5. Only afterwards is work scheduled.
  if anfrage.fortsetzen ≠ falsch:
      wecke(sitzung.id, umfang = Eingabe)

  return { aufgenommen: aufgenommen, neu: ja }
```

**Notes:**

* The order *Idempotency → Preparation → Admission → Scheduling* is
  binding. In particular, an **already known** content is not prepared again
  (no hooks, no attachment resolution).
* Preparation runs **interruptibly**, the rest does not. An interruption
  leaves **no** half-admission.
* `veroeffentliche` is atomic: event + inbox row arise together or not at all.

---

## B. Wake and execution coordination

**Postcondition:** If the session is inactive, an execution starts; if it is
active, the work is remembered for one more pass.

```
wecke(sitzungsID, umfang) =
    laufend ← aktive_ausfuehrung(sitzungsID)
    if laufend existiert:
        laufend.tuerklingel ← vereinige(laufend.tuerklingel, umfang)   # "Input" dominates "Control"
        return
    starte_ausfuehrung(sitzungsID, umfang)

starte_ausfuehrung(sitzungsID, umfang) =
    ausfuehrung ← neues_gehaeuse(sitzungsID, umfang)
    laufende_ausfuehrungen[sitzungsID] ← ausfuehrung

    unterbrechbar {
        # one tick delay: the owner is assigned before the run can fail
        warte_einen_tick()

        maskierend {
            veroeffentliche(ExecutionStarted { sitzungsID })   # sets the claim in the same transaction
        }

        wiederhol {
            ergebnis ← drain(sitzungsID, ausfuehrung.umfang, ausfuehrung.fortsetzung)
            if ausfuehrung.stoppend: brich ab
            if ausfuehrung.tuerklingel nicht gesetzt: brich ab
            ausfuehrung.umfang ← ausfuehrung.tuerklingel
            ausfuehrung.tuerklingel ← nicht gesetzt
            ausfuehrung.fortsetzung ← nicht gesetzt
        } bis ergebnis terminal

        # exactly one terminal event for the whole execution period
        maskierend {
            je nach ergebnis:
                Erfolg   → veroeffentliche(ExecutionSucceeded)
                Abbruch  → veroeffentliche(ExecutionInterrupted { grund: Nutzer oder Shutdown })
                Fehler e → veroeffentliche(ExecutionFailed { fehler: e })
        }
    }

    if ausfuehrung.tuerklingel gesetzt:     # work arrived during ending
        starte_ausfuehrung(sitzungsID, ausfuehrung.tuerklingel)   # follow-up execution
    else:
        entferne laufende_ausfuehrungen[sitzungsID]
```

**Notes:**

* Repeated wakes during a run are coalesced into **one** additional pass.
* The "doorbell" closes the gap between the last check in the pass and the
  idle transition – both cannot be one atomic step.
* The follow-up execution receives the work that arrived during ending.

---

## C. Pass (Drain)

**Postcondition:** Either the work is done (`Fertig`), or execution must
continue elsewhere (`Umgezogen`, `NeuGeladen`).

```
drain(sitzungsID, umfang, fortsetzung) =
    warte_auf_aktive_plugins()

    # clean up orphaned state from an earlier process
    rueume_verwaiste_verdichtungen(sitzungsID)
    rueume_verwaiste_werkzeugaufrufe(sitzungsID)

    weiter := wahr
    schritt := fortsetzung.schritt oder 1
    betrete := wahr

    solange weiter:
        ergebnis ← grenze_erreichen(sitzungsID, umfang, schritt, betrete, fortsetzung)
        if ergebnis.typ ≠ Bereit:
            return ergebnis
        fortsetzung ← ergebnis.fortsetzung
        weitermachen ← run_step(ergebnis.kontext, schritt)
        schritt ← schritt + 1
        betrete ← falsch
```

---

## D. Boundary logic (`grenze_erreichen`)

This is the part that decides **when** something happens: delivery,
compaction, move, or nothing at all.

```
grenze_erreichen(sitzungsID, umfang, schritt, betrete, fortsetzung) =
    schleife:
        if standort_geschlossen():
            schließe_transport(sitzungsID)
            return { typ: NeuGeladen, forsetzung: fortsetzung }

        # -- Control elements take precedence --
        serialisiert_für(sitzungsID) {
            naechstes ← wähle_zustellbares(sitzungsID, umfang = (betrete oder nicht fortsetzung) ? Eingabe : Steuern)

            if naechstes.typ = Verdichtung:
                veroeffentliche_alle([
                    InboxDelivered { inboxID: naechstes.id },
                    CompactionStarted { grund: Manuell, eingabeID: naechstes.id }
                ])
                fuehre_verdichtung_aus(naechstes)
                # the loop continues: after compaction the basis is new

            if naechstes.typ = Umzug:
                schließe_transport(sitzungsID)
                veroeffentliche_alle([
                    InboxDelivered { inboxID: naechstes.id },
                    SessionMoved { ... }
                ])
                return { typ: Umgezogen, forsetzung: fortsetzung }
        }

        # -- Reset the step when delivery is not control --
        if nicht fortsetzung und naechstes.zustellmodus ≠ Steuern:
            betrete ← wahr
            schritt ← 1

        # -- nothing to do? --
        if nicht erzwungen und nicht fortsetzung
           und (naechstes fehlt oder (naechstes.zustellmodus = Warteschlange und umfang = Steuern)):
            return { typ: Fertig }

        # -- prepare context and then deliver --
        auswahl ← select_context(sitzungsID)         # fail(AgentNichtGefunden)
        anzahl ← befoerder(sitzungsID, zustellumfang = (betrete und nicht fortsetzung) ? umfang : Steuern)

        if anzahl = undefiniert:
            weiter   # a control element took over this boundary

        if anzahl > 0 und sitzung.kein_elternteil und sitzung.unbenannt:
            starte_titelgenerierung(sitzungsID)      # in the background, does not block

        if anzahl > 0:
            schritt ← 1

        geladen ← lade_kontext(auswahl)
        return { typ: Bereit, kontext: geladen }
```

**Notes:**

* **Controls** (`Steuern`) are delivered at the next **safe** boundary –
  with precedence over parked (`Warteschlange`) inputs.
* **Control elements** (compaction, move) form a **boundary**: they are
  delivered before everything else, even before older controls, so their text
  lands literally and not in a summary.
* A parked input is delivered only at a **quiet boundary** – and then
  **exactly one**, after which re-evaluation happens.
* The step counter restarts when a new input is delivered.

---

## E. Delivery (`befoerder`)

**Postcondition:** Delivered inputs are visible; waiting ones stay waiting.

```
befoerder(sitzungsID, umfang) =
    serialisiert_für(sitzungsID) {
        steuerungen ← alle_inbox_einträge_mit_modus(Steuern)

        if steuerungen nicht leer oder umfang = Steuern:
            grenze ← index_des_ersten_control_elements(steuerungen)
            if grenze = 0:
                return undefiniert      # the caller must handle the control element
            veroeffentliche_für_alle(steuerungen[0 .. grenze-1], InboxDelivered)
            return anzahl_der_zugestellten

        # only at the quiet boundary: exactly one parked input
        geparkt ← älteste_inbox_einträge_mit_modus(Warteschlange)     # 1 entry
        if geparkt fehlt:
            return 0
        if geparkt.typ = Control:
            return undefiniert
        veroeffentliche(InboxDelivered { inboxID: geparkt.id })

        # pick up newly arrived controls in the meantime
        neu ← alle_inbox_einträge_mit_modus(Steuern)
        grenze ← index_des_ersten_control_elements(neu)
        veroeffentliche_für_alle(neu[0 .. grenze-1], InboxDelivered)

        return anzahl_der_zugestellten
    }
```

> **Serialization:** Admission, delivery and cancellation of the same session
> must not overtake each other. They are serialized via a **per-session lock**
> – sufficient process-locally.

---

## F. Selecting and loading context

```
select_context(sitzungsID) =
    sitzung ← lade_sitzung(sitzungsID)
    if sitzung.standort ≠ aktueller_standort:
        unterbreche()                      # the execution belongs elsewhere

    aktualisiere_mcp_werkzeuge()

    agent ← wähle_agent(sitzung.agent)     # fail(AgentNichtGefunden)
    rechte ← vereinige(agent.rechte, sitzung.rechte)

    parallel {
        werkzeuge ← werkzeug_snapshot(rechte)
        eingebaut ← lade_eingebaute_instructions()
        entdeckt  ← lade_umgebungs_instructions()
        fähigkeiten ← lade_faehigkeits_instructions(rechte)
        verweise  ← lade_verweis_instructions()
        mcp       ← lade_mcp_instructions(rechte)
        einträge  ← lade_sitzungs_eintraege(sitzungsID)
    }

    anweisungen ← vereinige_instructions([
        code_mode, mcp, verweise, fähigkeiten, entdeckt, eingebaut, einträge
    ])

    return { sitzung, agent, anweisungen, werkzeuge }

lade_kontext(auswahl) =
    modell ← löse_modell(auswahl.sitzung)        # fail(ModellNichtGewählt / ModellNichtVerfügbar)
    atomisch {
        historie ← lade_historie(sitzungsID, anweisungen, modell)
        grundlage ← lade_instruction_grundlage(sitzungsID, anweisungen)
    }
    return { …auswahl, modell, grundlage, historie }
```

**Notes:**

* The order of instruction sources is **cache-relevant**: first
  cross-cutting catalog contents, then project-specific, then mutable values
  (date, environment).
* The instruction basis and the history are read in **one** transaction –
  otherwise they could drift apart.

---

## G. Building the request

```
baue_anfrage(kontext, schritt) =
    system  ← [ agent.system oder basis_prompt(werkzeugnamen), kontext.grundlage ]
    nachrichten ← kontext.historie

    grenze_erreicht ← agent.maximale_schritte und schritt ≥ agent.maximale_schritte
    if grenze_erreicht:
        nachrichten ← nachrichten + [aufforderung_zusammenfassung]
        werkzeugwahl ← keine
    else:
        werkzeugwahl ← keine_angabe

    anfrage ← {
        system, nachrichten,
        werkzeuge: kontext.werkzeuge,
        modell: kontext.modell,
        ausgabebudget: berechne_ausgabebudget(kontext.modell, kontext.eingangsgröße),
        kopfzeilen: [ sitzungs-, projekt- und affinitätsbezogene Kennungen ],
        zwischenspeicherschlüssel: kontext.affinität
    }

    # Extension hooks may change request/tools/options
    geändert ← hook("kontext", anfrage)
    # Tool renames are traced back via object identity:
    # a hook may move a definition; invented entries drop out
    anfrage ← binde_werkzeuge(geändert, kontext.werkzeuge)
    return anfrage
```

> **The texts behind `basis_prompt` and `aufforderung_zusammenfassung`**
> are given with identifier, order and wording in
> [`13-internal-prompts.md`](./13-internal-prompts.md). That file also states
> that the step-limit prompt is inserted as an **assistant** message.

---

## H. Executing a step

```
run_step(kontext, schritt) =
    weitere_versuche ← wahr
    assistant ← neue_nachrichten_id()

    solange weitere_versuche:
        geladen ← lade_kontext(select_context(kontext.sitzung.id))

        # automatic compaction before the request blows the window
        verdichtung ← verdichte(grund = Automatisch, geladen)
        if verdichtung fehlgeschlagen:
            fail(SchrittFehlgeschlagen(verdichtung.fehler))
        if verdichtung.durchgeführt:
            assistant ← neue_nachrichten_id()
            weiter               # retry with new basis

        anfrage ← baue_anfrage(geladen, schritt)
        ergebnis ← versuch(geladen, anfrage, assistant)

        je nach ergebnis.typ:
            Fertig:
                return ergebnis.weiter_machen
            Wiederholung:
                warte_bis(ergebnis.zeitpunkt)        # publishes "Retry scheduled"
                assistant bleibt gleich
            Fortsetzung:
                warte_bis(ergebnis.zeitpunkt)
                veroeffentliche(SyntheticRaised { text: "Continue without retry" })
                assistant ← neue_nachrichten_id()
            Ausgabelimit:
                if ausgabe_limiter_erschöpft:
                    fail(SchrittFehlgeschlagen(Ausgabelimit))
                veroeffentliche(SyntheticRaised { text: "Split the work" })
                assistant ← neue_nachrichten_id()
            Verdichtet:
                assistant ← neue_nachrichten_id()
            Voll neu aufgebaut:
                weiter
```

---

## I. Attempt (one model call)

```
versuch(kontext, anfrage, assistant) =
    startbild ← erfasse_startzustand()          # parallel to the request
    werkzeuglaeufe ← []

    strom ← starte_modellaufruf(anfrage)

    for eachs ereignis im strom:
        if ereignis = Werkzeugaufruf_angefordert und nicht vom_anbieter_ausgeführt:
            # Important: execution starts AFTER publishing "called"
            lauf ← starte_nebenläufig {
                maskierend {
                    ergebnis ← werkzeug_ausfuehren(ereignis, kontext)
                    if ergebnis erfolgreich:
                        veroeffentliche(ToolSuccess { … })
                        return gekuerzt(ergebnis)
                    if ergebnis fehler:
                        veroeffentliche(ToolFailed { fehler: klassifiziere(ergebnis.fehler) })
                }
            }
            werkzeuglaeufe ← werkzeuglaeufe + [lauf]

    warte_alle(werkzeuglaeufe)

    je nach ende:
        Fehler_vor_ausgabe:
            if klassifizierbar_als_wiederholbar:
                return { typ: Wiederholung, … }
        Fehler_nach_ausgabe:
            if klassifizierbar_als_wiederholbar:
                return { typ: Fortsetzung, … }
        Kontextueberlauf_vor_ausgabe:
            if verdichtung_möglich:
                return { typ: Verdichtet }
            # otherwise terminal
        Ablehnung_durch_nutzer:
            return { typ: Fertig, weiter_machen: nein }
        Normal:
            veroeffentliche(StepEnded { verbrauch, kosten, token, bild, dateien })
            return { typ: Fertig, weiter_machen: (noch offene werkzeugaufrufe) }
```

---

## J. Tool execution

```
werkzeug_ausfuehren(aufruf, kontext) =
    kontext ← { sitzungsID, agent, nachrichtenID, aufrufID }

    # 1. Pre-hook may change the input (only hook that may fail)
    ereignis ← hook("werkzeug_vor", aufruf.name, aufruf.eingabe)
    if ereignis abgelehnt:
        fail(WerkzeugFehler { meldung: ereignis.meldung })

    # 2. Only the tools advertised in this request are callable
    definition ← definitione_von(ereignis.name)
    if definition fehlt:
        fail(WerkzeugFehler { meldung: "In diesem Request nicht verfügbar" })
    if unbekannter_name:
        fail(WerkzeugFehler { meldung: "Kein Werkzeug mit diesem Namen" })

    # 3. Decode input — on error do NOT execute
    eingabe ← dekodiere(definition.eingabe, ereignis.eingabe)
    if fehler:
        fail(WerkzeugFehler { meldung: "Ungültige Argumente: … bitte korrigieren und erneut aufrufen" })

    # 4. Leaf tool: resolution, permissions, side effect — in this order
    ergebnis ← definition.ausfuehren(eingabe, kontext)

    # 5. Encode output
    if definition.ausgabeschema:
        ausgabe ← kodiere(definition.ausgabeschema, ergebnis.ausgabe)
        if fehler:
            fail(WerkzeugFehler { meldung: "Ungültiger Ausgabewert" })

    # 6. Post-hook may change the result (must never fail)
    ergebnis ← hook("werkzeug_nach", ergebnis)

    # 7. Normalize content (empty ⇒ derive from output)
    inhalt ← normalisiere_inhalt(ergebnis.inhalt, ergebnis.ausgabe)

    return { ausgabe: ergebnis.ausgabe, inhalt, metadaten: ergebnis.metadaten }
```

---

## K. Permission check

```
pruefe_rechte(anfrage) =
    # 1. Assemble rule set
    regeln ← agent_rechte(anfrage.agent) + sitzungs_rechte(anfrage.sitzungID)
    if regeln leer:
        regeln ← [ {aktion: alles, ressource: alles, wirkung: Verweigern} ]

    # 2. Denial first — it beats any stored approval
    if irgendeine_ressource_ergibt(regeln, anfrage.aktion, anfrage.ressourcen, Verweigern):
        fail(Verweigert { regeln, aktion, ressourcen })

    # 3. Add stored approvals (per project)
    alle ← regeln + gespeicherte_freigaben(projekt)

    # 4. Determine effect: ask dominates allow
    wirkung ← if irgendeine_ressource_ergibt(alle, …, Fragen) dann Fragen else Erlauben

    # 5. Extension hook may change effect and message
    ergebnis ← hook("rechte_bewerten", wirkung)
    if ergebnis.wirkung = Verweigern:
        fail(Verweigert { … })
    if ergebnis.wirkung = Erlauben:
        return erlauben

    # 6. Ask: publish request and wait
    anfrage_id ← anfrage.veroeffentliche(FrageGestellt)
    antwort ← warte_auf_antwort(anfrage_id)
    je nach antwort:
        Einmalig:   return erlauben
        Dauerhaft:  speichere_freigabe(projekt, aktion, antwort.vorschlag); return erlauben
        Ablehnen:
            if antwort.ruckmeldung:
                fail(AblehnungMitRueckmeldung { ruckmeldung })   # stays typed
            else:
                unterbreche_mit(Ablehnung)                      # special abort, not an ordinary error
```

**Notes:** The order 1→6 is the **permission chain**. Each layer can only
restrict, except the extension hook, which may also extend. Stored approvals
are evaluated **after** the denial check – otherwise "allow permanently"
would be a way around "deny".

---

## L. Recovery after process abort

```
wiederherstellen() =
    for each sitzung mit offenem Claim, die nicht lokal läuft:
        versuche ← zähle_wiederaufnahme(sitzung)     # durable, incremented BEFORE the run
        if versuche > maximum (10):
            veroeffentliche(ExecutionFailed { grund: "repeatedly interrupted" })
            # releases the claim and resets the counter
            weiter
        veroeffentliche(SyntheticRaised { text: "The server was restarted. Continuing." })
        starte_ausfuehrung(sitzung, umfang = Eingabe)

    # report running background work first, then start children
    for each hintergrundarbeit:
        je nach art:
            Shell:    melde_ergebnis(oder Abbruch durch Neustart)
            Subagent: melde_ergebnis, dann starte kind, falls nicht lokal läuft
```

**Notes:** Recovery is **at-least-once**. It prevents concurrent runs, but not
repeated side effects after an abort. The budget is **per attempt** (not per
session), so a process that keeps dying does not create a restart loop.

---

## M. Compaction (shortened)

```
verdichte(grund, kontext) =
    veroeffentliche(CompactionStarted { grund })
    fenster ← berechne_fenster(kontext)          # remainder + summarized old load
    antwort ← modellaufruf(fenster, auftrag = "summarize")
    if antwort fehler:
        veroeffentliche(CompactionFailed { fehler })
        return { durchgeführt: nein, fehler }
    speichere_zusammenfassung(antwort)            # full transcript is retained
    schiebe_epoch_start(auf exakte_sequenz)
    veroeffentliche(CompactionEnded { … })
    return { durchgeführt: ja }
```

---

## N. Ordering and concurrency guarantees

| # | Guarantee |
|---|---|
| G-01 | Admission before execution: the inbox row exists before work is scheduled. |
| G-02 | Delivery before visibility: only `InboxDelivered` makes input model-visible. |
| G-03 | `ToolCalled` before the side effect. |
| G-04 | Terminal event after the side effect. |
| G-05 | Tool calls of a step run in parallel, their **publication** stays in call order. |
| G-06 | One step = one model call; the next step reloads the history. |
| G-07 | Terminal events of the execution are published after cleanup (except on interrupt, where confirmation is immediate). |
| G-08 | Interruption ends running tool calls without discarding parked inputs. |

> **Decision (2026-10-22, L-B/L-C/L-D).** Tool calls run with at most 8
> concurrently per step (FIFO semaphore, configurable 1–32); results are
> published in call order (sequencer with buffer; on failure the sequence
> advances, no gap). Waiting inbox items have no TTL (Delivered/Cancelled =
> deleted).