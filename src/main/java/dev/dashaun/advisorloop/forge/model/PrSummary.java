package dev.dashaun.advisorloop.forge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PrSummary(int number, String title, String url, String headRefName, String baseRefName, Instant createdAt,
		String state) {
}
