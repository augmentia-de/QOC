# qoc

Minimales Maven-Projekt mit **Java 21**, **Quarkus 3.40.1.1** und
**LangChain4j** (Quarkus-Extension).

## Voraussetzungen

- JDK 21
- Maven 3.9+

## Starten

```bash
export OPENAI_API_KEY="sk-..."
mvn quarkus:dev
```

Der Entwicklungsmodus läuft auf <http://localhost:8080>.

Ohne gesetzte `OPENAI_API_KEY` startet die Anwendung mit dem Platzhalter
`demo-key`; echte Modellaufrufe schlagen dann fehl. Der Platzhalter ist nur
für Entwicklung und Tests gedacht.

## Endpunkte

| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/hello` | einfacher Funktionstest |
| `POST` | `/api/chat` | Nachricht an das Modell |

```bash
curl http://localhost:8080/hello

curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Erkläre in einem Satz, was Quarkus ist."}'
```

## Tests und Build

```bash
mvn test
mvn package
```

Das Ergebnis liegt unter `target/quarkus-app/` und lässt sich so starten:

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

## Dokumentation

Die Spezifikation, Roadmap und Architekturanalyse liegen unter
[`docs/`](./docs/README.md). Der Einstieg für die Implementierung ist
[`docs/ROADMAP.md`](./docs/ROADMAP.md).

## Aufbau

```
qoc/
├── docs/                               # Spezifikation und Roadmap
├── pom.xml
└── src/
    ├── main/
    │   ├── java/de/augmentia/qoc/
    │   │   ├── Assistant.java          # LangChain4j-AI-Service
    │   │   ├── ChatResource.java       # POST /api/chat
    │   │   └── GreetingResource.java   # GET /hello
    │   └── resources/application.properties
    └── test/java/de/augmentia/qoc/
        └── GreetingResourceTest.java
```

## Modell ändern

In `src/main/resources/application.properties`:

```properties
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini
quarkus.langchain4j.openai.chat-model.temperature=0.2
```

Weitere Anbieter (Anthropic, Azure OpenAI, Ollama, …) werden über die
jeweiligen Quarkus-LangChain4j-Extensions ergänzt; die BOM ist bereits
eingebunden.
