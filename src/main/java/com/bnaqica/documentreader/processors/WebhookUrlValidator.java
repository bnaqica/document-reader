package com.bnaqica.documentreader.processors;

import com.bnaqica.documentreader.exception.InvalidWebhookUrlException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Checks only that a webhook URL is absolute http or https with a host (§7.1). There is deliberately no DNS lookup and
 * no address or port restriction: webhook security is deferred for the proof of concept (§22).
 */
@Component
public class WebhookUrlValidator {

    private static final Set<String> SCHEMES = Set.of("http", "https");

    /** @throws InvalidWebhookUrlException for anything else, including null or blank */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidWebhookUrlException();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidWebhookUrlException();
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new InvalidWebhookUrlException();
        }
    }
}
