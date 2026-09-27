package com.magicpin.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "keepalive.enabled", havingValue = "true")
public class RenderKeepAliveScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(RenderKeepAliveScheduler.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final URI healthUri;

    public RenderKeepAliveScheduler(
            @Value("${keepalive.url:}") String publicUrl
    ) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        this.healthUri = healthUri(publicUrl);
    }

    @Scheduled(
            fixedDelayString = "${keepalive.interval-ms:600000}",
            initialDelayString = "${keepalive.initial-delay-ms:600000}"
    )
    public void pingHealth() {
        if (healthUri == null) {
            log.warn("Keep-alive is enabled but no public service URL is configured.");
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .GET()
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<Void> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "Keep-alive health request returned status {}.",
                        response.statusCode()
                );
            }
        } catch (Exception e) {
            log.warn("Keep-alive health request failed: {}", e.getMessage());
        }
    }

    private static URI healthUri(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return null;
        }

        try {
            String normalized = publicUrl.replaceFirst("/+$", "");
            return URI.create(normalized + "/healthz");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}