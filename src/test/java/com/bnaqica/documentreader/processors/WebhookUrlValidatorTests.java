package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.exception.InvalidWebhookUrlException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlValidatorTests {

    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "/hooks/done",
                "hooks/done",
                "ftp://example.com/hook",
                "mailto:someone@example.com",
                "https:///hook",
                "https://exa mple.com/hook",
                "http://[::1"
            })
    void refusesAnythingButAbsoluteHttpOrHttps(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessage("webhookUrl must be an absolute http or https URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/hook", "http://localhost:9000/hook", "HTTPS://Example.com/a?b=c"})
    void acceptsHttpAndHttpsUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }
}
