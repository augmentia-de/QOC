# Ideas

## Wrapper-REST-Methode: Prompt in einer ephemeren Session

### Ziel

Eine einzelne REST-Methode soll:

1. eine neue Session anlegen,
2. den übergebenen Prompt ausführen,
3. das Ergebnis zurückgeben,
4. die Session danach automatisch wieder löschen.

Damit kann ein Client eine Aufgabe mit einem einzigen Aufruf ausführen, ohne Session-ID-Management.

### Vorgeschlagener Endpoint

```http
POST /api/run
Content-Type: application/json

{
  "agent": "build",
  "text": "Nenne die Hauptstadt von Frankreich in einem Wort.",
  "deleteOnComplete": true
}
```

Mögliche Antwort:

```json
{
  "finish": "NORMAL",
  "inputTokens": 123,
  "outputTokens": 2,
  "text": "Paris",
  "sessionId": null,
  "durationMs": 456
}
```

### Verhalten

- Wenn `agent` fehlt, wird der Primär-Agent verwendet.
- Wenn `deleteOnComplete=true`, wird die Session nach der Ausführung gelöscht.
- Wenn das Löschen fehlschlägt, wird die `sessionId` in der Antwort zurückgegeben.
- Laufende oder fehlgeschlagene Sessions können optional behalten werden (`retainOnError`).
- Die Methode soll intern `createSession`, `prompt` und `deleteSession` nutzen.

### Offene Fragen

- Soll die Antwort die `sessionId` immer zurückgeben oder nur bei Fehlern?
- Soll die Methode Agent-Definitionen per Name oder Inline-Definition unterstützen?
- Soll sie denselben Request-Body wie der spätere Task-Gateway nutzen?
- Soll sie SSE-Events für den Lauf bereitstellen oder nur JSON zurückgeben?
- Wie soll Timeout behandelt werden?
- Soll die Session bei einem Fehler automatisch gelöscht werden?

### Weitere Ideen

- Batch-Ausführung mehrerer Prompts in einer Session
- Agent-Wechsel vor der Ausführung
- Workspace pro Aufruf isolieren
- Idempotency-Key für wiederholte Aufrufe
- Audit-Log für ephemere Läufe
- Optionaler persistenter Modus statt automatischer Löschung
- Kompatibilität mit späterem Task-/Execution-Gateway
```
```

Maybe ensure no weird trailing. Write.<|end|><|start|>start_of_turn<|channel|>assistant to=write<|channel|>analysis <|constrain|>xml<|message|><rfl:parameter name="path">
/home/torsten/dev/my-projects/2oc/qoc/docs/ideas.md