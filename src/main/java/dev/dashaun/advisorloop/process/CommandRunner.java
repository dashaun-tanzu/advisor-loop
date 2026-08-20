package dev.dashaun.advisorloop.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Every external process (git, gh, advisor) is funnelled through this interface so that
 * tests can substitute a fake. {@code env} entries are added to the child process
 * environment; the advisor calls use it to point at the local mapping store.
 */
public interface CommandRunner {

	CommandResult run(Path workingDir, Duration timeout, Map<String, String> env, List<String> argv);

	default CommandResult run(Path workingDir, Duration timeout, List<String> argv) {
		return run(workingDir, timeout, Map.of(), argv);
	}

	default CommandResult run(Path workingDir, Duration timeout, String... argv) {
		return run(workingDir, timeout, Map.of(), List.of(argv));
	}

	default CommandResult runOrThrow(Path workingDir, Duration timeout, List<String> argv) {
		CommandResult r = run(workingDir, timeout, Map.of(), argv);
		if (!r.ok())
			throw new CommandFailedException(argv, r);
		return r;
	}

	default CommandResult runOrThrow(Path workingDir, Duration timeout, String... argv) {
		return runOrThrow(workingDir, timeout, List.of(argv));
	}

}
