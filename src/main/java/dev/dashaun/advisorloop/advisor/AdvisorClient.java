package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.process.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed wrapper over the {@code advisor} CLI (1.6.7).
 *
 * <p>
 * The authoritative signal after any invocation is the content of
 * {@code .advisor/errors/}, not the exit code: advisor can exit non-zero with no error
 * files, and can write error files while exiting zero. {@link #classify} reconciles the
 * two.
 */
@Component
public class AdvisorClient {

	private static final Logger log = LoggerFactory.getLogger(AdvisorClient.class);

	/**
	 * The only phrase that means "there is nothing to upgrade".
	 *
	 * <p>
	 * Deliberately a single exact marker. An earlier version also matched "up to date"
	 * and "up-to-date", which silently suppressed the upgrade path on every repository:
	 * advisor prints "Existing build-configuration is already up-to-date" at the top of a
	 * perfectly good 10-step plan, and that phrase refers to the build-config file, not
	 * to the dependencies.
	 *
	 * <p>
	 * The failure modes are asymmetric, which is why narrow wins. A false positive here
	 * skips upgrades silently and forever. A false negative merely runs upgrade-plan
	 * apply, finds no diff, and opens no PR - visible and harmless.
	 */
	private static final String NO_UPGRADE_MARKER = "no upgrade plans available";

	private final CommandRunner runner;

	private final AdvisorProperties props;

	private final AdvisorEnv env;

	public AdvisorClient(CommandRunner runner, AdvisorProperties props, AdvisorEnv env) {
		this.runner = runner;
		this.props = props;
		this.env = env;
	}

	public AdvisorRunResult buildConfigGet(Path repoDir) {
		return exec(repoDir, false, "build-config", "get");
	}

	public AdvisorRunResult upgradePlanGet(Path repoDir) {
		return exec(repoDir, true, "upgrade-plan", "get");
	}

	/**
	 * Applies the first step of the upgrade plan. Advisor 1.6.7 applies step one by
	 * default; the {@code --step} flag of earlier releases no longer exists.
	 */
	public AdvisorRunResult upgradePlanApply(Path repoDir) {
		return exec(repoDir, false, "upgrade-plan", "apply");
	}

	/** Applies the latest patch-level upgrades to all dependencies in a single pass. */
	public AdvisorRunResult patchApply(Path repoDir) {
		return exec(repoDir, false, "patch", "apply");
	}

	/**
	 * Generates a mapping for {@code coordinate}. Advisor writes the result into
	 * {@code <workDir>/.advisor/mappings/}; the caller is responsible for moving it into
	 * the store.
	 */
	public CommandResult mappingCreate(Path workDir, String coordinate) {
		return runner.run(workDir, timeout(), env.mappingEnv(),
				List.of(props.advisorBinary(), "mapping", "create", "-c", coordinate));
	}

	private AdvisorRunResult exec(Path repoDir, boolean checkNoUpgrade, String... args) {
		clearErrorsDir(repoDir);
		List<String> argv = new ArrayList<>();
		argv.add(props.advisorBinary());
		argv.addAll(List.of(args));
		CommandResult r = runner.run(repoDir, timeout(), env.mappingEnv(), argv);
		return classify(repoDir, r, checkNoUpgrade);
	}

	AdvisorRunResult classify(Path repoDir, CommandResult r, boolean checkNoUpgrade) {
		List<String> errors = readErrorContents(repoDir);
		if (!errors.isEmpty()) {
			return new AdvisorRunResult(AdvisorOutcome.ERRORED, r, errors);
		}
		if (!r.ok()) {
			// Nothing in .advisor/errors but the command failed: synthesize from the
			// output.
			String text = r.stderr() == null || r.stderr().isBlank() ? r.stdout() : r.stderr();
			return new AdvisorRunResult(AdvisorOutcome.ERRORED, r, List.of(text == null ? "" : text));
		}
		if (checkNoUpgrade && noUpgradeDetected(r)) {
			return new AdvisorRunResult(AdvisorOutcome.NO_UPGRADE_AVAILABLE, r, List.of());
		}
		return new AdvisorRunResult(AdvisorOutcome.OK, r, List.of());
	}

	private static boolean noUpgradeDetected(CommandResult r) {
		return r.combined().toLowerCase().contains(NO_UPGRADE_MARKER);
	}

	private static void clearErrorsDir(Path repoDir) {
		Path errorsDir = repoDir.resolve(".advisor/errors");
		if (!Files.isDirectory(errorsDir))
			return;
		try (var s = Files.list(errorsDir)) {
			s.forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException ignored) {
					/* best effort */ }
			});
		}
		catch (IOException e) {
			log.warn("Could not clear {}: {}", errorsDir, e.getMessage());
		}
	}

	private static List<String> readErrorContents(Path repoDir) {
		Path errorsDir = repoDir.resolve(".advisor/errors");
		if (!Files.isDirectory(errorsDir))
			return List.of();
		List<String> out = new ArrayList<>();
		try (var s = Files.list(errorsDir)) {
			for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
				try {
					out.add(Files.readString(p));
				}
				catch (IOException e) {
					out.add("(unreadable " + p.getFileName() + ": " + e.getMessage() + ")");
				}
			}
		}
		catch (IOException e) {
			log.warn("Could not list {}: {}", errorsDir, e.getMessage());
		}
		return out;
	}

	private Duration timeout() {
		return props.process().timeoutPerCommand();
	}

}
