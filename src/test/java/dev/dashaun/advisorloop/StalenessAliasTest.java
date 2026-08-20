package dev.dashaun.advisorloop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StalenessAliasTest {

	@AfterEach
	void clear() {
		System.clearProperty("advisor.staleness");
	}

	private static String resolve(String... args) {
		AdvisorLoopApplication.applyStalenessAlias(args);
		return System.getProperty("advisor.staleness");
	}

	@Test
	void a_plain_duration_is_accepted() {
		assertThat(resolve("--staleness=1h")).isEqualTo("PT1H");
	}

	@Test
	void minutes_and_days_work_too() {
		assertThat(resolve("--staleness=30m")).isEqualTo("PT30M");
		System.clearProperty("advisor.staleness");
		assertThat(resolve("--staleness=24h")).isEqualTo("PT24H");
	}

	@Test
	void the_iso_form_is_still_accepted() {
		assertThat(resolve("--staleness=PT6H")).isEqualTo("PT6H");
	}

	@Test
	void zero_forces_every_bot_pr_to_be_recreated() {
		assertThat(resolve("--staleness=0s")).isEqualTo("PT0S");
	}

	@Test
	void without_the_flag_the_configured_default_stands() {
		assertThat(resolve("--orgs=acme")).isNull();
	}

	@Test
	void an_unparseable_value_falls_back_rather_than_crashing() {
		// Better to run with the 24h default than to abort the whole pass over a typo.
		assertThat(resolve("--staleness=banana")).isNull();
	}

}
