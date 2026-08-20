package dev.dashaun.advisorloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "advisor")
public record AdvisorProperties(List<String> orgs,
		/** A bot PR at least this old is deleted and recreated. */
		Duration staleness,
		/** Root of all work; everything lives under here. */
		Path workspace, String botPrefix, String advisorBinary, String gitBinary, String ghBinary, String githubHost,
		GitLab gitlab, String defaultBranchFallback,
		/**
		 * Phrase introducing advisor's list of dependencies that need a mapping. It
		 * appears on the SUCCESS path of upgrade-plan get, never as an error.
		 */
		String unmappedMarker, boolean skipArchived, boolean skipForks, boolean dryRun, Mappings mappings,
		Branches branches, ProcessTimeouts process, GitIdentity git, List<ErrorRule> errorRules) {

	/** GitLab-specific settings; GitHub keeps its historical top-level keys. */
	public record GitLab(List<String> groups, String host, String binary, boolean includeSubgroups) {
	}

	/** Local, git-free mapping store consumed by advisor through env vars. */
	public record Mappings(String dirName, String workDirName, String mergeStrategy) {
	}

	public record Branches(String upgradePrefix, String patchPrefix) {
	}

	public record ProcessTimeouts(Duration timeoutPerCommand) {
	}

	public record GitIdentity(String userName, String userEmail) {
	}

	public record ErrorRule(String pattern, String kind, int coordGroup) {
	}

	public Path mappingsDir() {
		return workspace.resolve(mappings.dirName());
	}

	public Path mappingWorkDir() {
		return workspace.resolve(mappings.workDirName());
	}
}
