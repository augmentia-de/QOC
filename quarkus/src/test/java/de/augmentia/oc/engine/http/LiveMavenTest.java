package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(LiveMavenTest.Profile.class)
class LiveMavenTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.provider.scripted", "false");
            String key = System.getenv("OPENAI_API_KEY");
            String url = System.getenv("OPENAI_BASE_URL");
            String model = System.getenv("OPENAI_MODEL");
            if (key != null) overrides.put("engine.provider.api-key", key);
            if (url != null) overrides.put("engine.provider.url", url + "/chat/completions");
            if (model != null) overrides.put("engine.model.id", model);
            overrides.put("engine.workspace", "/tmp/oc-live");
            return overrides;
        }
    }

    @Test
    void createsAndCompilesAMavenHelloWorldProject() throws Exception {
        Path workspace = Path.of("/tmp/oc-live");
        deleteRecursively(workspace);
        Files.createDirectories(workspace);

        String id = given()
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        io.restassured.response.Response response = given()
                .contentType("application/json")
                .body(Map.of("text",
                        "Du MUSST sofort das write-Tool aufrufen, fuer JEDE Datei einzeln. Keine Erklaerung, keine Analyse. "
                                + "Datei 1: path=pom.xml content=<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\"><modelVersion>4.0.0</modelVersion><groupId>de.example</groupId><artifactId>hello</artifactId><version>1.0.0</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version><configuration><archive><manifest><mainClass>de.example.App</mainClass></manifest></archive></configuration></plugin></plugins></build></project>. "
                                + "Datei 2: path=src/main/java/de/example/App.java content=package de.example;\n\npublic class App {\n    public static void main(String[] args) {\n        System.out.println(Greeter.greet(\"World\"));\n    }\n}. "
                                + "Datei 3: path=src/main/java/de/example/Greeter.java content=package de.example;\n\npublic class Greeter {\n    public static String greet(String name) {\n        return \"Hello, \" + name + \"!\";\n    }\n}. "
                                + "Rufe jetzt write fuer pom.xml auf."))
                .post("/api/session/" + id + "/prompt");

        System.out.println("Maven-project finish: " + response.jsonPath().getString("finish"));
        System.out.println("Maven-project text: " + response.jsonPath().getString("text"));

        assertThat(response.statusCode()).isEqualTo(200);

        Path pom = workspace.resolve("pom.xml");
        Path app = workspace.resolve("src/main/java/de/example/App.java");
        Path greeter = workspace.resolve("src/main/java/de/example/Greeter.java");

        assertThat(Files.exists(pom)).isTrue();
        assertThat(Files.exists(app)).isTrue();
        assertThat(Files.exists(greeter)).isTrue();
        assertThat(Files.readString(pom)).contains("<artifactId>hello</artifactId>");
        assertThat(Files.readString(app)).contains("public static void main");

        // Compile the generated project to prove it is valid.
        ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "compile");
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        System.out.println("mvn compile exit: " + process.exitValue());
        System.out.println(output);
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (Exception ignored) { }
            });
        }
    }
}
