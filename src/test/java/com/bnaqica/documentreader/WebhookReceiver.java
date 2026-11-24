package com.bnaqica.documentreader;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A local webhook endpoint that answers 200 and keeps every POST it gets, by path. */
public final class WebhookReceiver implements AutoCloseable {

    public record Delivery(String path, String batchHeader, String body) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    public WebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                deliveries.add(new Delivery(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Document-Reader-Batch"),
                        body));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
    }

    /** A fresh URL on this receiver, so each test reads only its own deliveries. */
    public String newUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/hooks/" + UUID.randomUUID();
    }

    public List<Delivery> deliveriesTo(String url) {
        String path = url.substring(url.indexOf("/hooks/"));
        return deliveries.stream()
                .filter(delivery -> delivery.path().equals(path))
                .toList();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
