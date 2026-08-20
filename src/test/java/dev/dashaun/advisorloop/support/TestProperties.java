package dev.dashaun.advisorloop.support;

import dev.dashaun.advisorloop.config.AdvisorProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Builds an {@link AdvisorProperties} mirroring application.yml, for use in unit tests.
 */
public final class TestProperties {

	public static final List<AdvisorProperties.ErrorRule> RULES = List.of(rule(
			"no\\s+mapping\\s+(?:file\\s+)?(?:was\\s+)?(?:found|available|defined)\\s+for\\s+.{0,40}?([\\w.\\-]+:[\\w.\\-]+)",
			"MISSING_MAPPING", 1),
			rule("mapping\\s+(?:is\\s+)?not\\s+found.{0,40}?([\\w.\\-]+:[\\w.\\-]+)", "MISSING_MAPPING", 1),
			rule("missing\\s+mapping.{0,40}?([\\w.\\-]+:[\\w.\\-]+)", "MISSING_MAPPING", 1),
			rule("could\\s+not\\s+find\\s+(?:a\\s+)?mapping.{0,40}?([\\w.\\-]+:[\\w.\\-]+)", "MISSING_MAPPING", 1),
			rule("unsupported\\s+(?:library|dependency).{0,40}?([\\w.\\-]+:[\\w.\\-]+)", "MISSING_MAPPING", 1),
			rule("(?:duplicate|conflicting|overlapping|ambiguous)\\s+\\w*\\s*mapping", "BAD_MAPPING", 0),
			rule("mapping[^\\n]{0,60}(?:duplicate|conflict|overlap|ambiguous|invalid|malformed)", "BAD_MAPPING", 0),
			rule("(?:failed|unable)\\s+to\\s+(?:parse|read|load|deserialize)[^\\n]{0,60}mapping", "BAD_MAPPING", 0),
			rule("SPRING_ADVISOR_MAPPING_CUSTOM", "BAD_MAPPING", 0));

	private TestProperties() {
	}

	private static AdvisorProperties.ErrorRule rule(String pattern, String kind, int group) {
		return new AdvisorProperties.ErrorRule(pattern, kind, group);
	}

	public static AdvisorProperties at(Path workspace) {
		return new AdvisorProperties(List.of("acme"), Duration.ofHours(24), workspace, "[AdvisorBot]", "advisor", "git",
				"gh", "github.com", new AdvisorProperties.GitLab(List.of(), "gitlab.com", "glab", true), "main",
				"configure the projects of the following dependencies", true, true, false,
				new AdvisorProperties.Mappings("mappings", ".mapping-work", "override"),
				new AdvisorProperties.Branches("advisorbot/upgrade-", "advisorbot/patch-"),
				new AdvisorProperties.ProcessTimeouts(Duration.ofMinutes(15)),
				new AdvisorProperties.GitIdentity("AdvisorBot", "advisorbot@dashaun.dev"), RULES);
	}

}
