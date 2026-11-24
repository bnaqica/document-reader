package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Sha256Tests {

    @Test
    void hexOfKnownInput() throws Exception {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);

        assertThat(Sha256.hex(new ByteArrayInputStream(abc)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hexOfEmptyInput() throws Exception {
        assertThat(Sha256.hex(new ByteArrayInputStream(new byte[0])))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
