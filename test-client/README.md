# QOC Test Client

Simple Spring Boot client for testing QOC through REST and MCP.

## Run
1. Start QOC, e.g. on `http://localhost:8099`.
2. Start this client:
   ```bash
   cd test-client
   mvn spring-boot:run
   ```
3. Open `http://localhost:8088`.

The UI lets you choose REST or MCP mode, select an agent, send a prompt, and inspect the result.
