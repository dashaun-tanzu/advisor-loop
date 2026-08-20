package dev.dashaun.advisorloop.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBuilderCommandRunnerTest {

	private final ProcessBuilderCommandRunner runner = new ProcessBuilderCommandRunner();

	@Test
	void a_missing_binary_reports_not_found_instead_of_throwing() {
		// gh, glab and advisor are all external prerequisites; a missing one must not
		// unwind the
		// pass, or the run exits silently because there is no console appender.
		CommandResult r = runner.run(null, Duration.ofSeconds(10),
				List.of("definitely-not-a-real-binary-xyz", "--version"));

		assertThat(r.ok()).isFalse();
		assertThat(r.exitCode()).isEqualTo(ProcessBuilderCommandRunner.NOT_FOUND_EXIT);
		assertThat(r.stderr()).contains("definitely-not-a-real-binary-xyz");
	}

	@Test
	void it_captures_stdout_and_the_exit_code() {
		CommandResult r = runner.run(null, Duration.ofSeconds(10), List.of("echo", "hello"));

		assertThat(r.ok()).isTrue();
		assertThat(r.stdout()).contains("hello");
	}

	@Test
	void a_non_zero_exit_is_reported_not_thrown() {
		CommandResult r = runner.run(null, Duration.ofSeconds(10), List.of("false"));

		assertThat(r.ok()).isFalse();
		assertThat(r.exitCode()).isNotZero();
	}

	@Test
	void environment_entries_reach_the_child_process() {
		// This is how advisor is pointed at the local mapping store.
		CommandResult r = runner.run(null, Duration.ofSeconds(10), java.util.Map.of("ADVISOR_LOOP_PROBE", "visible"),
				List.of("sh", "-c", "echo $ADVISOR_LOOP_PROBE"));

		assertThat(r.stdout()).contains("visible");
	}

}
