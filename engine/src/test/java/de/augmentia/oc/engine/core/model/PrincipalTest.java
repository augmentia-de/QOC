package de.augmentia.oc.engine.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalTest {

    @Test
    void anonymous() {
        Principal p = Principal.anonymous();
        assertThat(p.id()).isEqualTo("anonymous");
        assertThat(p.name()).isEqualTo("anonymous");
        assertThat(p.roles()).isEmpty();
        assertThat(p.groups()).isEmpty();
        assertThat(p.claims()).isEmpty();
        assertThat(p.accessToken()).isNull();
        assertThat(p.isAdmin()).isFalse();
    }

    @Test
    void localAdmin() {
        Principal p = Principal.localAdmin();
        assertThat(p.id()).isEqualTo("local");
        assertThat(p.isAdmin()).isTrue();
    }

    @Test
    void system() {
        Principal p = Principal.system();
        assertThat(p.id()).isEqualTo("system");
        assertThat(p.roles()).contains("system");
        assertThat(p.isAdmin()).isFalse();
    }

    @Test
    void nullFieldsNormalized() {
        Principal p = new Principal(null, null, null, null, null, null);
        assertThat(p.id()).isEqualTo("anonymous");
        assertThat(p.name()).isEqualTo("anonymous");
        assertThat(p.roles()).isEmpty();
        assertThat(p.groups()).isEmpty();
        assertThat(p.claims()).isEmpty();
    }

    @Test
    void blankIdNormalized() {
        Principal p = new Principal("  ", "  ", List.of(), List.of(), Map.of(), "tok");
        assertThat(p.id()).isEqualTo("anonymous");
        assertThat(p.name()).isEqualTo("anonymous");
    }

    @Test
    void listsAreCopied() {
        List<String> roles = new java.util.ArrayList<>(List.of("admin"));
        Principal p = new Principal("id", "name", roles, List.of(), Map.of(), null);
        roles.add("extra");
        assertThat(p.roles()).containsExactly("admin");
    }

    @Test
    void isAdminCaseInsensitive() {
        Principal p = new Principal("id", "name", List.of("ADMIN"), List.of(), Map.of(), null);
        assertThat(p.isAdmin()).isTrue();
    }
}
