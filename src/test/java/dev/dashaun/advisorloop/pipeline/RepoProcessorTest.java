package dev.dashaun.advisorloop.pipeline;

import dev.dashaun.advisorloop.advisor.AdvisorClient;
import dev.dashaun.advisorloop.advisor.AdvisorErrorClassifier;
import dev.dashaun.advisorloop.advisor.AdvisorOutcome;
import dev.dashaun.advisorloop.advisor.AdvisorRunResult;
import dev.dashaun.advisorloop.advisor.UnmappedDependencyScanner;
import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.git.GitClient;
import dev.dashaun.advisorloop.forge.Forge;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.forge.model.PrSummary;
import dev.dashaun.advisorloop.mapping.MappingAddition;
import dev.dashaun.advisorloop.mapping.MappingStore;
import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.support.TestProperties;
import dev.dashaun.advisorloop.ui.ActivityReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoProcessorTest {

	private static final String SLUG = "acme/widget";

	private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

	@TempDir
	Path workspace;

	private AdvisorProperties props;

	private GitClient git;

	private ForgeClient gh;

	private AdvisorClient advisor;

	private MappingStore mappings;

	private ByteArrayOutputStream out;

	private RepoProcessor processor;

	private Path repoDir;

	@BeforeEach
	void setUp() throws IOException {
		props = TestProperties.at(workspace);
		git = mock(GitClient.class);
		gh = mock(ForgeClient.class);
		when(gh.forge()).thenReturn(Forge.GITHUB);
		advisor = mock(AdvisorClient.class);
		mappings = mock(MappingStore.class);
		out = new ByteArrayOutputStream();
		ActivityReporter reporter = new ActivityReporter(new PrintStream(out, true, StandardCharsets.UTF_8));
		processor = new RepoProcessor(props, git, advisor, new AdvisorErrorClassifier(props),
				new UnmappedDependencyScanner(props), mappings, reporter, Clock.fixed(NOW, ZoneOffset.UTC));

		repoDir = workspace.resolve("acme").resolve("widget");
		Files.createDirectories(repoDir);
		Files.writeString(repoDir.resolve("pom.xml"), "<project/>");

		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of());
		when(advisor.buildConfigGet(any())).thenReturn(ok());
		when(advisor.upgradePlanGet(any())).thenReturn(ok());
		when(advisor.upgradePlanApply(any())).thenReturn(ok());
		when(advisor.patchApply(any())).thenReturn(ok());
		when(git.hasChanges(any())).thenReturn(true);
	}

	private String output() {
		return out.toString(StandardCharsets.UTF_8);
	}

	private static AdvisorRunResult ok() {
		return new AdvisorRunResult(AdvisorOutcome.OK, new CommandResult(0, "plan", "", Duration.ZERO), List.of());
	}

	private static AdvisorRunResult noUpgrade() {
		return new AdvisorRunResult(AdvisorOutcome.NO_UPGRADE_AVAILABLE,
				new CommandResult(0, "no upgrade available", "", Duration.ZERO), List.of());
	}

	private static AdvisorRunResult errored(String message) {
		return new AdvisorRunResult(AdvisorOutcome.ERRORED, new CommandResult(1, "", message, Duration.ZERO),
				List.of(message));
	}

	private static PrSummary pr(int number, String head, Instant createdAt) {
		return new PrSummary(number, "[AdvisorBot] something", "https://x/" + number, head, "main", createdAt, "OPEN");
	}

	private MappingAddition addition(String coordinate) {
		return new MappingAddition(coordinate, List.of(workspace.resolve("mappings/x.json")), Map.of());
	}

	@Test
	void a_healthy_repo_opens_both_an_upgrade_and_a_patch_pr() {
		processor.process(gh, SLUG, "main", false);

		assertThat(output()).contains("upgrade PR", "patch PR");
		verify(gh).createPr(eq(SLUG), eq("main"), org.mockito.ArgumentMatchers.startsWith("advisorbot/upgrade-"),
				anyString(), anyString(), eq(false));
		verify(gh).createPr(eq(SLUG), eq("main"), org.mockito.ArgumentMatchers.startsWith("advisorbot/patch-"),
				anyString(), anyString(), eq(false));
	}

	@Test
	void the_patch_branch_starts_from_a_clean_default_branch_so_the_prs_do_not_stack() {
		processor.process(gh, SLUG, "main", false);

		// Without this reset the patch PR would also contain the upgrade commit.
		verify(git).resetTo(repoDir, "main");
	}

	@Test
	void a_missing_mapping_is_generated_and_then_validated_by_a_second_upgrade_plan_get() {
		when(advisor.upgradePlanGet(any())).thenReturn(errored("no mapping found for io.acme:widget-core"))
			.thenReturn(ok());
		when(mappings.create("io.acme:widget-core")).thenReturn(Optional.of(addition("io.acme:widget-core")));

		processor.process(gh, SLUG, "main", false);

		verify(mappings).create("io.acme:widget-core");
		verify(mappings, never()).rollback(any());
		verify(advisor, times(2)).upgradePlanGet(repoDir);
		assertThat(output()).contains("mapping create", "upgrade PR");
	}

	@Test
	void mappings_are_generated_one_at_a_time() {
		when(advisor.upgradePlanGet(any())).thenReturn(errored("no mapping found for io.acme:one"))
			.thenReturn(errored("no mapping found for io.acme:two"))
			.thenReturn(ok());
		when(mappings.create(anyString())).thenAnswer(i -> Optional.of(addition(i.getArgument(0))));

		processor.process(gh, SLUG, "main", false);

		verify(mappings).create("io.acme:one");
		verify(mappings).create("io.acme:two");
		// The first mapping was accepted once advisor moved on to asking for the second.
		verify(mappings, never()).rollback(any());
	}

	@Test
	void an_unmapped_dependency_reported_on_the_success_path_triggers_mapping_create() {
		// Advisor exits 0, writes no error files, names the coordinate, then claims there
		// is
		// nothing to upgrade. Before the fix this repo silently got no mapping and no
		// upgrade.
		String unmappedOutput = """
				Please request your administrator to configure the projects of the following dependencies:
					- io.pivotal.spring.cloud:cloudfoundry-certificate-truster
						uses:
							- spring-framework
				No upgrade plans available - your project seems to be up to date.
				""";
		when(advisor.upgradePlanGet(any()))
			.thenReturn(new AdvisorRunResult(AdvisorOutcome.NO_UPGRADE_AVAILABLE,
					new CommandResult(0, unmappedOutput, "", Duration.ZERO), List.of()))
			.thenReturn(ok());
		when(mappings.create("io.pivotal.spring.cloud:cloudfoundry-certificate-truster"))
			.thenReturn(Optional.of(addition("io.pivotal.spring.cloud:cloudfoundry-certificate-truster")));

		processor.process(gh, SLUG, "main", false);

		verify(mappings).create("io.pivotal.spring.cloud:cloudfoundry-certificate-truster");
		verify(mappings, never()).rollback(any());
		assertThat(output()).contains("mapping create", "upgrade PR");
	}

	@Test
	void a_mapping_that_advisor_still_asks_for_is_rolled_back() {
		// The generated mapping did not satisfy advisor, so it must not be left in the
		// store.
		String stillAsking = """
				Please request your administrator to configure the projects of the following dependencies:
					- io.acme:stubborn
				No upgrade plans available - your project seems to be up to date.
				""";
		MappingAddition added = addition("io.acme:stubborn");
		when(advisor.upgradePlanGet(any())).thenReturn(new AdvisorRunResult(AdvisorOutcome.NO_UPGRADE_AVAILABLE,
				new CommandResult(0, stillAsking, "", Duration.ZERO), List.of()));
		when(mappings.create("io.acme:stubborn")).thenReturn(Optional.of(added));

		processor.process(gh, SLUG, "main", false);

		verify(mappings, times(1)).create("io.acme:stubborn");
		verify(mappings).rollback(added);
		assertThat(output()).contains("mapping rollback");
	}

	@Test
	void the_budget_is_the_size_of_advisors_first_list() {
		// Advisor names every unmappable dependency up front, so a repo's turn is
		// budgeted at
		// that count. A fixed cap of 5 meant a 46-dependency project needed ten separate
		// runs.
		// Each round advisor drops the coordinate that has just been mapped, as the real
		// CLI does.
		when(advisor.upgradePlanGet(any())).thenReturn(asking("io.acme:one", "io.acme:two", "io.acme:three"))
			.thenReturn(asking("io.acme:two", "io.acme:three"))
			.thenReturn(asking("io.acme:three"))
			.thenReturn(ok());
		when(mappings.create(anyString())).thenAnswer(i -> Optional.of(addition(i.getArgument(0))));

		processor.process(gh, SLUG, "main", false);

		verify(mappings).create("io.acme:one");
		verify(mappings).create("io.acme:two");
		verify(mappings).create("io.acme:three");
		verify(mappings, never()).rollback(any());
		assertThat(output()).contains("upgrade PR");
	}

	@Test
	void the_budget_stops_the_loop_when_advisor_keeps_naming_new_dependencies() {
		// The first list had two entries, so two attempts is the whole turn even if
		// advisor
		// keeps finding more. The rest are left for the next pass.
		when(advisor.upgradePlanGet(any())).thenReturn(asking("io.acme:one", "io.acme:two"))
			.thenReturn(asking("io.acme:two", "io.acme:three"))
			.thenReturn(asking("io.acme:three", "io.acme:four"))
			.thenReturn(asking("io.acme:four", "io.acme:five"));
		when(mappings.create(anyString())).thenAnswer(i -> Optional.of(addition(i.getArgument(0))));

		processor.process(gh, SLUG, "main", false);

		verify(mappings, times(2)).create(anyString());
	}

	/** Advisor's success-path output naming dependencies that need a mapping. */
	private static AdvisorRunResult asking(String... coordinates) {
		StringBuilder sb = new StringBuilder(
				"Please request your administrator to configure the projects of the following dependencies:\n");
		for (String c : coordinates) {
			sb.append("\t- ").append(c).append("\n\t\tuses:\n\t\t\t- spring-framework\n");
		}
		sb.append("No upgrade plans available - your project seems to be up to date.\n");
		return new AdvisorRunResult(AdvisorOutcome.NO_UPGRADE_AVAILABLE,
				new CommandResult(0, sb.toString(), "", Duration.ZERO), List.of());
	}

	@Test
	void a_genuinely_up_to_date_repo_generates_no_mappings() {
		when(advisor.upgradePlanGet(any())).thenReturn(noUpgrade());

		processor.process(gh, SLUG, "main", false);

		verify(mappings, never()).create(anyString());
		assertThat(output()).contains("patch PR");
	}

	@Test
	void a_mapping_that_breaks_the_mapping_files_is_rolled_back() {
		MappingAddition added = addition("io.acme:widget-core");
		when(advisor.upgradePlanGet(any())).thenReturn(errored("no mapping found for io.acme:widget-core"))
			.thenReturn(errored("Duplicate mapping for slug widget"));
		when(mappings.create("io.acme:widget-core")).thenReturn(Optional.of(added));

		processor.process(gh, SLUG, "main", false);

		verify(mappings).rollback(added);
		assertThat(output()).contains("mapping rollback");
	}

	@Test
	void a_mapping_that_does_not_resolve_the_error_is_rolled_back() {
		MappingAddition added = addition("io.acme:widget-core");
		// Advisor asks for the same coordinate again: the generated mapping did not help.
		when(advisor.upgradePlanGet(any())).thenReturn(errored("no mapping found for io.acme:widget-core"))
			.thenReturn(errored("no mapping found for io.acme:widget-core"));
		when(mappings.create("io.acme:widget-core")).thenReturn(Optional.of(added));

		processor.process(gh, SLUG, "main", false);

		verify(mappings, times(1)).create("io.acme:widget-core");
		verify(mappings).rollback(added);
	}

	@Test
	void no_upgrade_available_still_attempts_a_patch() {
		when(advisor.upgradePlanGet(any())).thenReturn(noUpgrade());

		processor.process(gh, SLUG, "main", false);

		verify(advisor, never()).upgradePlanApply(any());
		verify(advisor).patchApply(repoDir);
		assertThat(output()).contains("patch PR");
	}

	@Test
	void a_pr_older_than_the_staleness_window_is_deleted_and_recreated() {
		PrSummary stale = pr(7, "advisorbot/upgrade-20260101-000000", NOW.minus(Duration.ofHours(25)));
		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of(stale));

		processor.process(gh, SLUG, "main", false);

		verify(gh).closePr(SLUG, 7, false);
		verify(gh).deleteBranch(SLUG, "advisorbot/upgrade-20260101-000000", false);
		assertThat(output()).contains("stale PR delete");
		// and it is recreated in the same pass
		verify(gh).createPr(eq(SLUG), anyString(), org.mockito.ArgumentMatchers.startsWith("advisorbot/upgrade-"),
				anyString(), anyString(), anyBoolean());
	}

	@Test
	void a_fresh_pr_is_left_alone() {
		PrSummary fresh = pr(8, "advisorbot/upgrade-20260818-000000", NOW.minus(Duration.ofHours(3)));
		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of(fresh));

		processor.process(gh, SLUG, "main", false);

		verify(gh, never()).closePr(anyString(), anyInt(), anyBoolean());
		verify(gh, never()).createPr(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void a_fresh_upgrade_pr_skips_the_repo_including_the_patch() {
		PrSummary freshUpgrade = pr(9, "advisorbot/upgrade-20260818-000000", NOW.minus(Duration.ofHours(2)));
		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of(freshUpgrade));

		processor.process(gh, SLUG, "main", false);

		verify(advisor, never()).patchApply(any());
		verify(gh, never()).createPr(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
		assertThat(output()).contains("fresh PR", "skip");
	}

	@Test
	void a_fresh_patch_pr_also_skips_the_upgrade() {
		// The point of the repo-wide rule: either kind of fresh PR is enough to stand
		// down.
		PrSummary freshPatch = pr(10, "advisorbot/patch-20260818-000000", NOW.minus(Duration.ofHours(2)));
		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of(freshPatch));

		processor.process(gh, SLUG, "main", false);

		verify(advisor, never()).upgradePlanApply(any());
		verify(gh, never()).createPr(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void a_stale_pr_is_not_deleted_while_a_fresh_one_stands() {
		// Deleting it would discard a PR this pass has already decided not to replace.
		PrSummary freshPatch = pr(11, "advisorbot/patch-20260818-000000", NOW.minus(Duration.ofHours(1)));
		PrSummary staleUpgrade = pr(12, "advisorbot/upgrade-20260101-000000", NOW.minus(Duration.ofHours(30)));
		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of(freshPatch, staleUpgrade));

		processor.process(gh, SLUG, "main", false);

		verify(gh, never()).closePr(anyString(), anyInt(), anyBoolean());
	}

	@Test
	void any_fresh_pr_skips_advisor_entirely() {
		PrSummary freshPatch = pr(10, "advisorbot/patch-20260818-000000", NOW.minus(Duration.ofHours(2)));
		when(gh.listOpenPrsByPrefix(anyString(), anyString())).thenReturn(List.of(freshPatch));

		processor.process(gh, SLUG, "main", false);

		// Advisor costs minutes per repository; a fresh PR must short-circuit before any
		// of it.
		verify(advisor, never()).buildConfigGet(any());
		verify(advisor, never()).upgradePlanGet(any());
		verify(advisor, never()).patchApply(any());
		assertThat(output()).contains("fresh PR", "skip");
	}

	@Test
	void a_repo_without_a_build_file_produces_no_output_at_all() throws IOException {
		Files.delete(repoDir.resolve("pom.xml"));

		processor.process(gh, SLUG, "main", false);

		assertThat(output()).isEmpty();
		verify(advisor, never()).buildConfigGet(any());
	}

	@Test
	void a_gradle_repo_is_in_scope() throws IOException {
		Files.delete(repoDir.resolve("pom.xml"));
		Files.writeString(repoDir.resolve("build.gradle"), "plugins {}");

		processor.process(gh, SLUG, "main", false);

		verify(advisor).buildConfigGet(repoDir);
	}

	@Test
	void when_advisor_makes_no_changes_no_pr_is_opened() {
		when(git.hasChanges(any())).thenReturn(false);

		processor.process(gh, SLUG, "main", false);

		verify(gh, never()).createPr(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void dry_run_never_pushes_or_opens_a_pr() {
		processor.process(gh, SLUG, "main", true);

		// Both PRs still go through the same call path; the dry-run flag is what
		// suppresses them.
		verify(git).push(any(), org.mockito.ArgumentMatchers.startsWith("advisorbot/upgrade-"), eq(true));
		verify(git).push(any(), org.mockito.ArgumentMatchers.startsWith("advisorbot/patch-"), eq(true));
		verify(gh, times(2)).createPr(anyString(), anyString(), anyString(), anyString(), anyString(), eq(true));
		verify(gh, never()).createPr(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false));
	}

	@Test
	void a_failed_build_config_stops_the_repo() {
		when(advisor.buildConfigGet(any())).thenReturn(errored("boom"));

		processor.process(gh, SLUG, "main", false);

		assertThat(output()).contains("build-config get", "fail");
		verify(advisor, never()).upgradePlanGet(any());
		verify(advisor, never()).patchApply(any());
	}

	@Test
	void an_empty_repository_is_not_counted_as_a_failure() {
		// Cloning an empty repo succeeds, then `checkout <default>` fails because there
		// is no commit.
		org.mockito.Mockito.doThrow(new IllegalStateException("pathspec 'main' did not match"))
			.when(git)
			.ensureFresh(any(), anyString(), any(), anyString());
		when(git.isCloned(any())).thenReturn(true);
		when(git.hasNoCommits(any())).thenReturn(true);

		processor.process(gh, SLUG, "main", false);

		assertThat(output()).isEmpty();
	}

	@Test
	void a_clone_failure_is_reported_and_does_not_throw() {
		org.mockito.Mockito.doThrow(new IllegalStateException("network"))
			.when(git)
			.ensureFresh(any(), anyString(), any(), anyString());
		when(git.isCloned(any())).thenReturn(true);
		when(git.hasNoCommits(any())).thenReturn(false); // a real repo, genuinely
															// unreachable

		processor.process(gh, SLUG, "main", false);

		assertThat(output()).contains("clone", "fail");
	}

}
