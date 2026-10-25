package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** File tree, upload/download and recursive delete through the HTTP surface. */
@QuarkusTest
class FileApiTest {

    private Path root;
    private String relative;

    @BeforeEach
    void setUp() throws Exception {
        relative = "target/file-api-" + UUID.randomUUID();
        root = Path.of(relative);
        Files.createDirectories(root.resolve("sub"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort cleanup
                }
            });
        }
    }

    @Test
    void createsListsUploadsDownloadsAndDeletes() {
        given().contentType(ContentType.JSON)
                .body(Map.of("path", relative + "/note.txt", "content", "hello"))
                .post("/api/file").then().statusCode(200);

        given().get("/api/files?path=" + relative)
                .then().statusCode(200)
                .body("data.name", hasItem("note.txt"))
                .body("data.find { it.name == 'note.txt' }.relative", equalTo(relative + "/note.txt"));

        byte[] binary = {0, 1, 2, 3, (byte) 0xff};
        given().contentType("application/octet-stream").body(binary)
                .post("/api/file/upload?path=" + relative + "/sub/bin.dat")
                .then().statusCode(200);

        byte[] downloaded = given().get("/api/file/download?path=" + relative + "/sub/bin.dat")
                .then().statusCode(200)
                .extract().asByteArray();
        assertThat(downloaded).isEqualTo(binary);

        byte[] zip = given().get("/api/dir/download?path=" + relative)
                .then().statusCode(200)
                .extract().asByteArray();
        assertThat(zip.length).isGreaterThan(4);
        assertThat(zip[0]).isEqualTo((byte) 'P');
        assertThat(zip[1]).isEqualTo((byte) 'K');

        given().delete("/api/file?path=" + relative)
                .then().statusCode(200)
                .body("ok", equalTo(true));
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void rejectsTraversalAndWorkspaceRootDeletion() {
        given().delete("/api/file?path=../outside")
                .then().statusCode(400);
        given().delete("/api/file?path=.")
                .then().statusCode(400);
    }
}
