package dev.dashaun.advisorloop.git;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.process.CommandFailedException;
import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.process.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class GitClient {

	private static final Logger log = LoggerFactory.getLogger(GitClient.class);

	/**
	 * Advisor writes {@code .advisor/} into the repository root and drives Maven/Gradle,
	 * which fill {@code target/} and {@code build/}. None of that belongs in a pull
	 * request, and left untracked it also makes {@code git status} look dirty when
	 * advisor changed nothing at all.
	 */
	private static final List<String> LOCAL_EXCLUDES = List.of(".advisor/", "target/", "build/");

	private final CommandRunner runner;

	private final AdvisorProperties props;

	public GitClient(CommandRunner runner, AdvisorProperties props) {
		this.runner = runner;
		this.props = props;
	}

	public boolean isCloned(Path repoDir) {
		return Files.exists(repoDir.resolve(".git"));
	}

	public void clone(ForgeClient forge, String slug, Path target) {
		try {
			Files.createDirectories(target.getParent());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create parent of " + target, e);
		}
		runner.runOrThrow(target.getParent(), timeout(), props.gitBinary(), "clone", forge.cloneUrl(slug),
				target.getFileName().toString());
	}

	/**
	 * Brings the working copy to a pristine copy of {@code defaultBranch}. Always resets
	 * and cleans, so a half-applied upgrade from an earlier pass is never inherited.
	 */
	public void ensureFresh(ForgeClient forge, String slug, Path target, String defaultBranch) {
		if (!isCloned(target)) {
			clone(forge, slug, target);
			checkout(target, defaultBranch);
			configureClone(forge, target);
			return;
		}
		try {
			runner.runOrThrow(target, timeout(), props.gitBinary(), "fetch", "--prune", "origin");
			resetTo(target, defaultBranch);
		}
		catch (CommandFailedException e) {
			log.warn("ensureFresh failed for {} ({}); re-cloning", slug, e.getMessage());
			deleteRecursively(target);
			clone(forge, slug, target);
			checkout(target, defaultBranch);
		}
		configureClone(forge, target);
	}

	/** Per-clone setup that must survive a re-clone: credentials and local excludes. */
	private void configureClone(ForgeClient forge, Path target) {
		useForgeCredentials(forge, target);
		excludeBuildArtifacts(target);
	}

	/**
	 * Routes HTTPS credentials for the forge's host through its own CLI ({@code gh auth
	 * git-credential} or {@code glab auth git-credential}).
	 *
	 * <p>
	 * Clones use HTTPS, but a machine configured for SSH git operations has no HTTPS
	 * credential, so a push dies with {@code could not read Username for 'https://...'}.
	 * Reads still succeed on public repos, which is why this only ever surfaces on the
	 * first real push. Delegating to the forge CLI reuses the authentication the rest of
	 * the bot already depends on, and setting it on the clone leaves the machine's global
	 * git config untouched.
	 */
	public void useForgeCredentials(ForgeClient forge, Path repoDir) {
		runner.run(repoDir, timeout(), props.gitBinary(), "config", "credential.https://" + forge.host() + ".helper",
				"!" + forge.cliBinary() + " auth git-credential");
	}

	/**
	 * Registers {@link #LOCAL_EXCLUDES} in the clone's {@code .git/info/exclude}. This is
	 * local to the clone and never committed. Excludes only apply to untracked files, so
	 * a repository that genuinely tracks one of these paths still reports its
	 * modifications.
	 */
	public void excludeBuildArtifacts(Path repoDir) {
		Path exclude = repoDir.resolve(".git/info/exclude");
		try {
			Files.createDirectories(exclude.getParent());
			List<String> existing = Files.exists(exclude) ? Files.readAllLines(exclude) : new ArrayList<>();
			List<String> missing = LOCAL_EXCLUDES.stream().filter(e -> !existing.contains(e)).toList();
			if (missing.isEmpty())
				return;
			StringBuilder sb = new StringBuilder();
			if (!existing.isEmpty() && !existing.get(existing.size() - 1).isBlank())
				sb.append('\n');
			sb.append("# added by advisor-loop\n");
			missing.forEach(e -> sb.append(e).append('\n'));
			Files.writeString(exclude, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e) {
			log.warn("Could not write local excludes for {}: {}", repoDir, e.getMessage());
		}
	}

	/** Discards all local work and returns to origin's copy of {@code branch}. */
	public void resetTo(Path repoDir, String branch) {
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "checkout", "--force", branch);
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "reset", "--hard", "origin/" + branch);
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "clean", "-fdx");
	}

	public void checkout(Path repoDir, String branch) {
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "checkout", branch);
	}

	/**
	 * True when the clone contains no commits, which is how an empty GitHub repository
	 * looks after a successful clone. Such a repo has nothing to upgrade and is not a
	 * failure.
	 */
	public boolean hasNoCommits(Path repoDir) {
		return !runner.run(repoDir, timeout(), props.gitBinary(), "rev-parse", "--verify", "HEAD").ok();
	}

	public void setIdentity(Path repoDir) {
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "config", "user.name", props.git().userName());
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "config", "user.email", props.git().userEmail());
	}

	public void checkoutNewBranch(Path repoDir, String branch) {
		CommandResult exists = runner.run(repoDir, timeout(), props.gitBinary(), "rev-parse", "--verify", branch);
		if (exists.ok()) {
			runner.run(repoDir, timeout(), props.gitBinary(), "branch", "-D", branch);
		}
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "checkout", "-b", branch);
	}

	public boolean hasChanges(Path repoDir) {
		CommandResult r = runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "status", "--porcelain");
		return !r.stdout().isBlank();
	}

	public void addAll(Path repoDir) {
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "add", "-A");
	}

	public void commit(Path repoDir, String message) {
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "commit", "-m", message);
	}

	public void push(Path repoDir, String branch, boolean dryRun) {
		if (dryRun) {
			log.info("[dry-run] git push origin {}", branch);
			return;
		}
		runner.runOrThrow(repoDir, timeout(), props.gitBinary(), "push", "-u", "origin", branch);
	}

	private Duration timeout() {
		return props.process().timeoutPerCommand();
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir))
			return;
		try (var s = Files.walk(dir)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
		catch (IOException e) {
			log.warn("Failed to delete {}: {}", dir, e.getMessage());
		}
	}

}
