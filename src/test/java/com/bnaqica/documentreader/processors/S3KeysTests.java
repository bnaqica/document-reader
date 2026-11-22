package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S3KeysTests {

    private static final UUID FILE = UUID.fromString("3f2a1b4c-0000-4000-8000-000000000001");

    private final S3Keys keys = new S3Keys("files/");

    @Test
    void originalKeyUsesSourceFileId() {
        assertThat(keys.original(FILE, "pdf")).isEqualTo("files/" + FILE + "/original.pdf");
        assertThat(keys.original(FILE, "PDF")).isEqualTo("files/" + FILE + "/original.pdf");
    }

    @Test
    void pageKeyIsZeroPadded() {
        assertThat(keys.page(FILE, 7)).isEqualTo("files/" + FILE + "/pages/0007.png");
        assertThat(keys.page(FILE, 9999)).endsWith("/pages/9999.png");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10_000})
    void pageOutOfRangeIsRefused(int page) {
        assertThatThrownBy(() -> keys.page(FILE, page)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tar.gz", "../x", "", "a/b", "abcdefghijk"})
    void extensionThatCouldCarryAPathOrNameIsRefused(String ext) {
        assertThatThrownBy(() -> keys.original(FILE, ext)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullExtensionIsRefused() {
        assertThatThrownBy(() -> keys.original(FILE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredPrefixIsUsed() {
        assertThat(new S3Keys("docs/").page(FILE, 1)).isEqualTo("docs/" + FILE + "/pages/0001.png");
    }
}
