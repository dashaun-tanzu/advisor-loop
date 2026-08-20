package dev.dashaun.advisorloop.forge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RepoSummary(String name, String nameWithOwner, String defaultBranch, boolean isArchived, boolean isFork,
		boolean isPrivate) {
}
