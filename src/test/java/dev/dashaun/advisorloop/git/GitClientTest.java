package dev.dashaun.advisorloop.git;

import dev.dashaun.advisorloop.process.ProcessBuilderCommandRunner;
import dev.dashaun.advisorloop.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.dashaun.advisorloop.forge.Forge;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.forge.model.PrSummary;
import dev.dashaun.advisorloop.forge.model.RepoSummary;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises real git, because the value of the local-excludes fix lies entirely in git's
 * own semantics: excludes suppress untracked noise but must never hide a tracked-file
 * change.
 */
class GitClientTest {

	@TempDir
	Path workspace;

	private GitClient git;

	private Path repo;

	@BeforeEach
	void setUp() throws IOException {
		ProcessBuilderCommandRunner runner = new ProcessBuilderCommandRunner();
		git = new GitClient(runner, TestProperties.at(workspace));
		repo = workspace.resolve("repo");
		Files.createDirectories(repo);
		Duration t = Duration.ofMinutes(1);
		runner.runOrThrow(repo, t, "git", "init", "-q");
		runner.runOrThrow(repo, t, "git", "config", "user.email", "t@example.com");
		runner.runOrThrow(repo, t, "git", "config", "user.name", "t");
		Files.writeString(repo.resolve("pom.xml"), "<project/>");
		runner.runOrThrow(repo, t, "git", "add", "-A");
		runner.runOrThrow(repo, t, "git", "commit", "-qm", "initial");
	}

	private void writeFile(String relative, String content) throws IOException {
		Path p = repo.resolve(relative);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content);
	}

	@Test
	void advisor_scratch_files_do_not_count_as_changes() throws IOException {
		// Reproduces the real observation: advisor leaves .advisor/ at the repository
		// root.
		writeFile(".advisor/patch-upgrade-rewrite.yml", "type: specs.openrewrite.org/v1beta/recipe");
		assertThat(git.hasChanges(repo)).as("untracked .advisor/ looks like a change").isTrue();

		git.excludeBuildArtifacts(repo);

		assertThat(git.hasChanges(repo)).as("advisor scratch must not trigger an empty pull request").isFalse();
	}

	@Test
	void build_output_does_not_count_as_changes() throws IOException {
		writeFile("target/app.jar", "binary");
		writeFile("build/libs/app.jar", "binary");

		git.excludeBuildArtifacts(repo);

		assertThat(git.hasChanges(repo)).isFalse();
	}

	@Test
	void a_real_source_change_is_still_detected() throws IOException {
		git.excludeBuildArtifacts(repo);
		writeFile(".advisor/scratch.yml", "noise");

		Files.writeString(repo.resolve("pom.xml"), "<project><modelVersion/></project>");

		assertThat(git.hasChanges(repo)).as("the upgrade itself must still be seen").isTrue();
	}

	@Test
	void a_tracked_file_is_never_hidden_by_the_excludes() throws IOException {
		// If a repository genuinely commits build output, excludes must not mask edits to
		// it.
		writeFile("target/keep.txt", "v1");
		new ProcessBuilderCommandRunner().runOrThrow(repo, Duration.ofMinutes(1), "git", "add", "-f",
				"target/keep.txt");
		new ProcessBuilderCommandRunner().runOrThrow(repo, Duration.ofMinutes(1), "git", "commit", "-qm",
				"track build output");
		git.excludeBuildArtifacts(repo);

		Files.writeString(repo.resolve("target/keep.txt"), "v2");

		assertThat(git.hasChanges(repo)).isTrue();
	}

	@Test
	void github_credentials_are_delegated_to_gh_on_the_clone_only() {
		// A machine set up for SSH has no HTTPS credential, so pushes would fail without
		// this.
		git.useForgeCredentials(forgeStub("github.com", "gh"), repo);

		ProcessBuilderCommandRunner runner = new ProcessBuilderCommandRunner();
		String local = runner
			.runOrThrow(repo, Duration.ofMinutes(1), "git", "config", "--local", "--get",
					"credential.https://github.com.helper")
			.stdout();
		assertThat(local).contains("gh auth git-credential");

		// It must not have leaked into the machine's global config.
		int globalExit = runner
			.run(repo, Duration.ofMinutes(1), "git", "config", "--global", "--get",
					"credential.https://github.com.helper")
			.exitCode();
		assertThat(globalExit).as("global git config must be untouched").isNotZero();
	}

	@Test
	void gitlab_credentials_are_delegated_to_glab_for_its_own_host() {
		git.useForgeCredentials(forgeStub("gitlab.example.com", "glab"), repo);

		String local = new ProcessBuilderCommandRunner()
			.runOrThrow(repo, Duration.ofMinutes(1), "git", "config", "--local", "--get",
					"credential.https://gitlab.example.com.helper")
			.stdout();
		assertThat(local).contains("glab auth git-credential");
	}

	/**
	 * Minimal ForgeClient: only host() and cliBinary() matter for credential
	 * configuration.
	 */
	private static ForgeClient forgeStub(String host, String binary) {
		return new ForgeClient() {
			@Override
			public Forge forge() {
				return Forge.GITHUB;
			}

			@Override
			public boolean authStatus() {
				return true;
			}

			@Override
			public List<RepoSummary> listRepos(String namespace) {
				return List.of();
			}

			@Override
			public String getDefaultBranch(String slug) {
				return "main";
			}

			@Override
			public List<PrSummary> listOpenPrsByPrefix(String slug, String prefix) {
				return List.of();
			}

			@Override
			public void closePr(String slug, int number, boolean dryRun) {
			}

			@Override
			public void deleteBranch(String slug, String branch, boolean dryRun) {
			}

			@Override
			public String createPr(String s1, String s2, String s3, String s4, String s5, boolean d) {
				return "";
			}

			@Override
			public String cloneUrl(String slug) {
				return "https://" + host + "/" + slug + ".git";
			}

			@Override
			public String host() {
				return host;
			}

			@Override
			public String cliBinary() {
				return binary;
			}
		};
	}

	@Test
	void applying_the_excludes_twice_does_not_duplicate_them() throws IOException {
		git.excludeBuildArtifacts(repo);
		git.excludeBuildArtifacts(repo);

		long advisorLines = Files.readAllLines(repo.resolve(".git/info/exclude"))
			.stream()
			.filter(l -> l.equals(".advisor/"))
			.count();
		assertThat(advisorLines).isEqualTo(1);
	}

}
