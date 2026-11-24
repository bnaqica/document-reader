package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.client.WebhookClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhook client: a JDK {@link HttpClient} that never follows redirects and gives up after {@code webhook.timeout}
 * to connect and to answer, and a {@code RestClient} that returns every status instead of throwing on one. The body is
 * written with the application's {@link JsonMapper}, so it matches what {@code GET /batches/{id}} returns.
 */
@Configuration
public class WebhookClientConfig {

    @Bean
    WebhookClient webhookClient(DocumentReaderProperties properties, JsonMapper jsonMapper) {
        Duration timeout = properties.webhook().timeout();
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        RestClient rest = RestClient.builder()
                .requestFactory(requests)
                .configureMessageConverters(converters -> converters
                        .registerDefaults()
                        .withJsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper)))
                // every status comes back as a response: the delivery service decides what counts as delivered
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(WebhookClient.class);
    }
}
