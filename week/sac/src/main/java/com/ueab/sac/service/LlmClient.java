package com.ueab.sac.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin client for any OpenAI-compatible chat API.
 * Enabled only when app.llm.api-key is set; otherwise callers fall back
 * to the built-in offline algorithms.
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    @Value("${app.llm.enabled:false}")
    private boolean enabled;

    @Value("${app.llm.api-url:}")
    private String apiUrl;

    @Value("${app.llm.api-key:}")
    private String apiKey;

    @Value("${app.llm.model:gpt-4o-mini}")
    private String model;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Truncates long error bodies so logs stay readable. */
    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Sends a chat completion request with an attached image (vision request).
     * The image must be PNG or JPEG bytes. Returns null when disabled or on any
     * error, so callers can fall back to local OCR processing.
     */
    public String chatWithImage(String systemPrompt, String userPrompt,
                                byte[] imageBytes, String imageMimeType) {
        if (!isEnabled() || imageBytes == null || imageBytes.length == 0) return null;
        try {
            String dataUrl = "data:" + imageMimeType + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);

            Map<String, Object> userContent = Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl));

            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", userPrompt),
                                    userContent))),
                    "temperature", 0.2);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(90))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("LLM request failed ({}): {}", response.statusCode(),
                        abbreviate(response.body()));
                return null;
            }

            var root = mapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("LLM request threw: {}", e.toString());
            return null;
        }
    }

    /**
     * Sends a chat completion request. Returns null when disabled or on any error,
     * so callers can fall back to local processing.
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (!isEnabled()) return null;
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)),
                    "temperature", 0.2);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("LLM request failed ({}): {}", response.statusCode(),
                        abbreviate(response.body()));
                return null;
            }

            var root = mapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("LLM request threw: {}", e.toString());
            return null;
        }
    }
}
