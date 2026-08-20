package dev.dashaun.advisorloop.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessBuilderCommandRunner implements CommandRunner {

	private static final Logger log = LoggerFactory.getLogger(ProcessBuilderCommandRunner.class);

	private static final int MAX_OUTPUT_BYTES = 1_048_576; // 1 MiB

	/** Shell convention for "command not found". */
	public static final int NOT_FOUND_EXIT = 127;

	private final ExecutorService streamPool = Executors
		.newThreadPerTaskExecutor(Thread.ofVirtual().name("cmd-stream-", 0L).factory());

	@Override
	public CommandResult run(Path workingDir, Duration timeout, Map<String, String> env, List<String> argv) {
		if (argv == null || argv.isEmpty()) {
			throw new IllegalArgumentException("argv must not be empty");
		}
		String cmdLine = String.join(" ", argv);
		log.debug("exec ({}): {}", workingDir, cmdLine);

		ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(false);
		if (workingDir != null)
			pb.directory(workingDir.toFile());
		// Scrub git env leaks that would redirect commands at the wrong repository.
		pb.environment()
			.keySet()
			.removeIf(k -> k.startsWith("GIT_DIR") || k.startsWith("GIT_INDEX_FILE") || k.startsWith("GIT_WORK_TREE"));
		if (env != null)
			pb.environment().putAll(env);

		Instant start = Instant.now();
		Process p;
		try {
			p = pb.start();
		}
		catch (IOException e) {
			// A missing CLI is an ordinary user error (advisor, gh and glab are all
			// external
			// prerequisites), so report it the way a shell does rather than unwinding the
			// pass.
			log.debug("could not start {}", cmdLine, e);
			return new CommandResult(NOT_FOUND_EXIT, "", "could not start '" + argv.get(0) + "': " + e.getMessage(),
					Duration.between(start, Instant.now()));
		}

		Future<String> stdoutF = streamPool.submit(() -> drain(p.getInputStream()));
		Future<String> stderrF = streamPool.submit(() -> drain(p.getErrorStream()));

		boolean finished;
		try {
			finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			destroy(p);
			throw new IllegalStateException("Interrupted while waiting for: " + cmdLine, e);
		}
		if (!finished) {
			destroy(p);
			throw new CommandTimeoutException(cmdLine, timeout);
		}

		String stdout = await(stdoutF);
		String stderr = await(stderrF);
		Duration elapsed = Duration.between(start, Instant.now());
		CommandResult result = new CommandResult(p.exitValue(), stdout, stderr, elapsed);
		if (!result.ok()) {
			log.debug("exec failed (exit {} in {}): {}\nstderr: {}", result.exitCode(), elapsed, cmdLine,
					result.shortStderr());
		}
		return result;
	}

	private static String drain(InputStream in) {
		StringBuilder sb = new StringBuilder();
		boolean truncated = false;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			int c;
			while ((c = r.read()) != -1) {
				if (sb.length() < MAX_OUTPUT_BYTES)
					sb.append((char) c);
				else
					truncated = true;
			}
		}
		catch (IOException ignored) {
			// process closed the stream; keep whatever was read
		}
		if (truncated) {
			sb.append("\n... [output truncated at ").append(MAX_OUTPUT_BYTES).append(" bytes]");
		}
		return sb.toString();
	}

	private static String await(Future<String> f) {
		try {
			return f.get(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		}
		catch (Exception e) {
			return "";
		}
	}

	private static void destroy(Process p) {
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
	}

}
