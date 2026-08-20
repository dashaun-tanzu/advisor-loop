package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.process.CommandRunner;
import dev.dashaun.advisorloop.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdvisorClientTest {

	@TempDir
	Path repo;

	private AdvisorClient advisor;

	@BeforeEach
	void setUp() {
		var props = TestProperties.at(repo);
		advisor = new AdvisorClient(mock(CommandRunner.class), props, new AdvisorEnv(props));
	}

	private AdvisorRunResult classifyStdout(String stdout) {
		return advisor.classify(repo, new CommandResult(0, stdout, "", Duration.ZERO), true);
	}

	/**
	 * Real `advisor upgrade-plan get` output captured from
	 * dashaun-demo/spring-petclinic-1.
	 */
	private static String realPlanOutput() throws IOException {
		try (InputStream in = AdvisorClientTest.class.getResourceAsStream("/upgrade-plan-get-with-plan.txt")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void a_real_plan_is_not_mistaken_for_no_upgrade() throws IOException {
		String output = realPlanOutput();
		// The trap: advisor opens a perfectly good plan with this line about the
		// build-config file.
		assertThat(output).contains("already up-to-date");
		assertThat(output).contains("Upgrade spring-boot");

		assertThat(classifyStdout(output).outcome())
			.as("matching 'up-to-date' here silently suppressed every upgrade PR")
			.isEqualTo(AdvisorOutcome.OK);
	}

	@Test
	void the_canonical_no_upgrade_phrase_is_detected() {
		assertThat(classifyStdout("No upgrade plans available").outcome())
			.isEqualTo(AdvisorOutcome.NO_UPGRADE_AVAILABLE);
	}

	@Test
	void the_no_upgrade_phrase_is_matched_case_insensitively() {
		assertThat(classifyStdout("no upgrade plans available for this project").outcome())
			.isEqualTo(AdvisorOutcome.NO_UPGRADE_AVAILABLE);
	}

	@Test
	void phrases_about_the_build_configuration_do_not_mean_no_upgrade() {
		// Each of these was previously a marker, and each appears alongside genuine
		// upgrades.
		for (String benign : new String[] { "Existing build-configuration is already up-to-date",
				"dependency is up to date", "already on the latest patch" }) {
			assertThat(classifyStdout(benign + "\n* Upgrade spring-boot from 3.4.x to 3.5.x").outcome())
				.as("%s must not suppress the upgrade path", benign)
				.isEqualTo(AdvisorOutcome.OK);
		}
	}

	@Test
	void a_non_zero_exit_without_error_files_still_errors() {
		assertThat(advisor.classify(repo, new CommandResult(1, "", "boom", Duration.ZERO), false).outcome())
			.isEqualTo(AdvisorOutcome.ERRORED);
	}

}
