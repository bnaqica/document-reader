package com.bnaqica.documentreader.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Identifies this instance in inbox leases ({@code locked_by}). */
@Configuration
public class WorkerConfig {

    /** The id this instance writes as {@code locked_by}: host name plus a random suffix, so two on one host differ. */
    public record WorkerInstance(String id) {}

    @Bean
    public WorkerInstance workerInstance() {
        return new WorkerInstance(hostName() + ":" + UUID.randomUUID());
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
