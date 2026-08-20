package dev.dashaun.advisorloop;

import dev.dashaun.advisorloop.advisor.AdvisorEnv;
import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.pipeline.Pipeline;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring and the values that are easy to break silently in application.yml.
 * The runner is disabled so the context loads without starting a pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = { "spring.main.web-application-type=none",
		// @SpringBootTest DOES execute ApplicationRunner beans, so without an empty org
		// list this
		// test would start a real, non-dry-run pass and open pull requests. Both guards
		// stay.
		"advisor.orgs=", "advisor.dry-run=true",
		// Never touch the real .workspace: a live run may be using it.
		"advisor.workspace=${user.dir}/target/test-workspace/.workspace" })
class ApplicationContextTest {

	@Autowired
	AdvisorProperties props;

	@Autowired
	Pipeline pipeline;

	@Autowired
	AdvisorEnv advisorEnv;

	@Test
	void the_context_loads_and_the_pipeline_is_wired() {
		assertThat(pipeline).isNotNull();
	}

	@Test
	void an_empty_org_list_refuses_to_run_rather_than_crawling_nothing_silently() {
		// This is also what stops the auto-started ApplicationRunner from touching GitHub
		// here.
		assertThat(pipeline.run(java.util.List.of(), true)).isEqualTo(2);
	}

	@Test
	void staleness_is_the_twenty_four_hour_window() {
		assertThat(props.staleness()).isEqualTo(Duration.ofHours(24));
	}

	@Test
	void the_mapping_store_sits_directly_under_the_workspace() {
		assertThat(props.workspace().getFileName().toString()).isEqualTo(".workspace");
		assertThat(props.mappingsDir()).isEqualTo(props.workspace().resolve("mappings"));
		assertThat(props.mappingWorkDir()).isEqualTo(props.workspace().resolve(".mapping-work"));
	}

	@Test
	void forks_are_in_scope_because_the_demo_org_is_entirely_forks() {
		assertThat(props.skipForks()).isFalse();
		assertThat(props.skipArchived()).isTrue();
	}

	@Test
	void both_forges_are_registered_and_addressable() {
		assertThat(pipeline).isNotNull();
		assertThat(props.gitlab().host()).isEqualTo("gitlab.com");
		assertThat(props.gitlab().binary()).isEqualTo("glab");
		assertThat(props.githubHost()).isEqualTo("github.com");
	}

	@Test
	void configured_targets_carry_their_forge() {
		// advisor.orgs are GitHub; advisor.gitlab.groups are GitLab. Here orgs is pinned
		// empty.
		assertThat(pipeline.configuredTargets()).isEmpty();
	}

	@Test
	void the_override_merge_strategy_is_configured() {
		assertThat(props.mappings().mergeStrategy()).isEqualTo("override");
	}

	@Test
	void every_error_rule_compiles_and_uses_a_known_kind() {
		assertThat(props.errorRules()).isNotEmpty();
		for (AdvisorProperties.ErrorRule rule : props.errorRules()) {
			java.util.regex.Pattern.compile(rule.pattern()); // throws if malformed
			assertThat(rule.kind()).isIn("MISSING_MAPPING", "BAD_MAPPING");
		}
	}

	@Test
	void an_empty_mapping_store_contributes_no_environment() {
		// Pointing advisor at an empty folder buys nothing, so both vars stay unset.
		assertThat(advisorEnv.mappingEnv()).isEmpty();
	}

	@Test
	void a_populated_mapping_store_sets_both_advisor_variables() throws Exception {
		Files.createDirectories(props.mappingsDir());
		Path probe = props.mappingsDir().resolve("context-test-probe.json");
		Files.writeString(probe, "{}");
		try {
			assertThat(advisorEnv.mappingEnv())
				.containsEntry("SPRING_ADVISOR_MAPPING_CUSTOM_0_MERGE_STRATEGY", "override")
				.containsKey("SPRING_ADVISOR_MAPPING_CUSTOM_0_FILEPATH");
		}
		finally {
			Files.deleteIfExists(probe);
		}
	}

}
