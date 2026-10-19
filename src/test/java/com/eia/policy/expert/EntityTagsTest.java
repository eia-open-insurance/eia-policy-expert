package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTagsTest {
    @Test
    void formatsStrongTagFromVersion() {
        assertThat(EntityTags.format(0)).isEqualTo("\"0\"");
        assertThat(EntityTags.format(42)).isEqualTo("\"42\"");
    }

    @Test
    void parsesStrongWeakUnquotedAndListForms() {
        assertThat(EntityTags.parse("\"7\"")).isEqualTo(7);
        assertThat(EntityTags.parse("W/\"7\"")).isEqualTo(7);
        assertThat(EntityTags.parse("7")).isEqualTo(7);
        assertThat(EntityTags.parse("\"7\", \"8\"")).isEqualTo(7);
        assertThat(EntityTags.parse(" * ")).isEqualTo(EntityTags.ANY);
        assertThat(EntityTags.parse(null)).isNull();
    }

    @Test
    void rejectsMalformedTags() {
        assertThatThrownBy(() -> EntityTags.parse("not-a-version"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_IF_MATCH");
                    assertThat(api.details()).anyMatch(issue -> issue.field().equals("If-Match"));
                });
        assertThatThrownBy(() -> EntityTags.parse(""))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> EntityTags.parse("\"-1\""))
                .isInstanceOf(ApiException.class);
    }
}
