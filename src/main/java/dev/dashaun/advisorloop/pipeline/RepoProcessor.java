package dev.dashaun.advisorloop.pipeline;

import dev.dashaun.advisorloop.advisor.AdvisorClient;
import dev.dashaun.advisorloop.advisor.AdvisorErrorClassifier;
import dev.dashaun.advisorloop.advisor.AdvisorRunResult;
import dev.dashaun.advisorloop.advisor.UnmappedDependencyScanner;
import dev.dashaun.advisorloop.advisor.ErrorKind;
import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.git.GitClient;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.forge.model.PrSummary;
import dev.dashaun.advisorloop.mapping.MappingAddition;
import dev.dashaun.advisorloop.mapping.MappingStore;
import dev.dashaun.advisorloop.ui.Activity;
import dev.dashaun.advisorloop.ui.ActivityReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs one repository through advisor, opening an upgrade PR and a patch PR
 * independently.
 *
 * <p>
 * The two PRs are branched separately off the default branch so they never stack, and a
 * fresh (under 24h) PR of one kind does not block the other.
 */
@Component
public class RepoProcessor {

	private static final Logger log = LoggerFactory.getLogger(RepoProcessor.class);

	private static final DateTimeFormatter BRANCH_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);

	private final AdvisorProperties props;

	private final GitClient git;

	private final AdvisorClient advisor;

	private final AdvisorErrorClassifier classifier;

	private final UnmappedDependencyScanner unmapped;

	private final MappingStore mappings;

	private final ActivityReporter reporter;

	private final Clock clock;

	public RepoProcessor(AdvisorProperties props, GitClient git, AdvisorClient advisor,
			AdvisorErrorClassifier classifier, UnmappedDependencyScanner unmapped, MappingStore mappings,
			ActivityReporter reporter, Clock clock) {
		this.props = props;
		this.git = git;
		this.advisor = advisor;
		this.classifier = classifier;
		this.unmapped = unmapped;
		this.mappings = mappings;
		this.reporter = reporter;
		this.clock = clock;
	}

	public void process(ForgeClient gh, String slug, String defaultBranch, boolean dryRun) {
		Path workdir = workdirFor(slug);
		if (workdir == null) {
			reporter.fail(slug, Activity.CLONE);
			return;
		}
		String branch = (defaultBranch == null || defaultBranch.isBlank()) ? gh.getDefaultBranch(slug) : defaultBranch;

		try {
			git.ensureFresh(gh, slug, workdir, branch);
		}
		catch (Exception e) {
			if (git.isCloned(workdir) && git.hasNoCommits(workdir)) {
				// An empty repository clones fine but has no branch to check out. Nothing
				// to do,
				// and reporting it as a failure would overstate the damage in the
				// summary.
				log.debug("{} is an empty repository; skipping", slug);
				return;
			}
			log.warn("clone/refresh failed for {}", slug, e);
			reporter.fail(slug, Activity.CLONE);
			return;
		}

		// Only Maven and Gradle projects are in scope; everything else is silently
		// ignored.
		if (!hasBuildFile(workdir)) {
			return;
		}

		// Any open bot PR younger than the staleness window means this pass has nothing
		// to add,
		// whether it is an upgrade PR or a patch PR. Returning before advisor runs is
		// what keeps
		// a re-run cheap: advisor is minutes per repository, everything above it is
		// seconds.
		if (hasFreshPr(gh, slug, dryRun)) {
			reporter.skip(slug, Activity.freshRequest(gh.forge()));
			return;
		}

		AdvisorRunResult buildConfig = advisor.buildConfigGet(workdir);
		if (buildConfig.errored()) {
			reporter.fail(slug, Activity.BUILD_CONFIG_GET);
			return;
		}
		reporter.success(slug, Activity.BUILD_CONFIG_GET);

		AdvisorRunResult plan = resolveUpgradePlan(slug, workdir);
		if (plan != null && plan.ok()) {
			runUpgrade(gh, slug, workdir, branch, dryRun);
		}

		runPatch(gh, slug, workdir, branch, dryRun);
	}

	/**
	 * Asks advisor for an upgrade plan, generating one missing mapping at a time until it
	 * either produces a plan or stops being fixable. Each {@code upgrade-plan get}
	 * doubles as the validation of the mapping added in the previous round: if the newest
	 * mapping does not lead to progress it is rolled back and the repository is left for
	 * a later pass.
	 * @return a usable plan, {@code null} when no plan could be produced
	 */
	private AdvisorRunResult resolveUpgradePlan(String slug, Path workdir) {
		Set<String> attempted = new HashSet<>();
		MappingAddition pending = null; // newest mapping, not yet proven good
		// Advisor names every unmappable dependency in its first answer, so that count is
		// the
		// natural budget for this repository's turn: enough to work through the whole set
		// once,
		// and inherently finite so a repo cannot loop forever.
		int budget = -1;

		for (int round = 0;; round++) {
			AdvisorRunResult plan = advisor.upgradePlanGet(workdir);
			if (!plan.errored()) {
				reporter.success(slug, Activity.UPGRADE_PLAN_GET);

				// Advisor reports unmappable dependencies here, on the success path, and
				// then
				// says "No upgrade plans available" regardless. Generating the mapping is
				// what
				// unblocks the upgrade, so this must be checked before the plan is
				// believed.
				List<String> stillUnmapped = unmapped.scan(plan.command().combined());
				if (budget < 0 && !stillUnmapped.isEmpty()) {
					budget = stillUnmapped.size();
					log.info("{} needs {} mapping(s)", slug, budget);
				}

				// A coordinate advisor asks for again is one the new mapping did not
				// satisfy.
				if (pending != null && stillUnmapped.contains(pending.coordinate())) {
					rollback(slug, pending);
					return null;
				}

				String coordinate = firstUntried(stillUnmapped, attempted, budget);
				if (coordinate != null) {
					Optional<MappingAddition> added = mappings.create(coordinate);
					if (added.isEmpty()) {
						reporter.fail(slug, Activity.MAPPING_CREATE);
						rollback(slug, pending);
						return null;
					}
					reporter.success(slug, Activity.MAPPING_CREATE);
					pending = added.get();
					continue;
				}
				return plan.noUpgrade() ? null : plan;
			}
			reporter.fail(slug, Activity.UPGRADE_PLAN_GET);

			ErrorKind kind = classifier.classify(plan.diagnosticText());
			if (kind instanceof ErrorKind.MissingMapping missing && attempted.add(missing.coordinate())) {
				Optional<MappingAddition> added = mappings.create(missing.coordinate());
				if (added.isEmpty()) {
					reporter.fail(slug, Activity.MAPPING_CREATE);
					rollback(slug, pending);
					return null;
				}
				reporter.success(slug, Activity.MAPPING_CREATE);
				// A new missing coordinate means the previous mapping was accepted by
				// advisor.
				pending = added.get();
				continue;
			}

			// Either the mapping files themselves are broken, the same coordinate came
			// back, or
			// the failure is unrelated. Undo the unproven mapping and move on.
			rollback(slug, pending);
			return null;
		}
	}

	/**
	 * First coordinate advisor asked to have configured that this repository has not
	 * already tried, or {@code null} when there is nothing left to attempt. One at a
	 * time, by design: the next {@code upgrade-plan get} is what proves the mapping good.
	 */
	private String firstUntried(List<String> coordinates, Set<String> attempted, int budget) {
		if (attempted.size() >= budget) {
			return null;
		}
		for (String coordinate : coordinates) {
			if (attempted.add(coordinate)) {
				return coordinate;
			}
		}
		return null;
	}

	private void rollback(String slug, MappingAddition pending) {
		if (pending == null)
			return;
		mappings.rollback(pending);
		reporter.success(slug, Activity.MAPPING_ROLLBACK);
	}

	private void runUpgrade(ForgeClient gh, String slug, Path workdir, String defaultBranch, boolean dryRun) {
		String branch = props.branches().upgradePrefix() + BRANCH_TS.format(clock.instant());
		if (!startBranch(slug, workdir, branch)) {
			reporter.fail(slug, Activity.UPGRADE_PLAN_APPLY);
			return;
		}
		AdvisorRunResult apply = advisor.upgradePlanApply(workdir);
		if (apply.errored()) {
			reporter.fail(slug, Activity.UPGRADE_PLAN_APPLY);
			return;
		}
		reporter.success(slug, Activity.UPGRADE_PLAN_APPLY);
		openPr(gh, slug, workdir, defaultBranch, branch, Activity.upgradeRequest(gh.forge()),
				props.botPrefix() + " Spring Boot upgrade",
				"Generated by advisor-loop with `advisor upgrade-plan apply`.", dryRun);
	}

	private void runPatch(ForgeClient gh, String slug, Path workdir, String defaultBranch, boolean dryRun) {
		// Start from a pristine default branch so the patch PR never contains the upgrade
		// commit.
		try {
			git.resetTo(workdir, defaultBranch);
		}
		catch (Exception e) {
			log.warn("reset before patch failed for {}", slug, e);
			reporter.fail(slug, Activity.PATCH_APPLY);
			return;
		}
		String branch = props.branches().patchPrefix() + BRANCH_TS.format(clock.instant());
		if (!startBranch(slug, workdir, branch)) {
			reporter.fail(slug, Activity.PATCH_APPLY);
			return;
		}
		AdvisorRunResult patch = advisor.patchApply(workdir);
		if (patch.errored()) {
			reporter.fail(slug, Activity.PATCH_APPLY);
			return;
		}
		reporter.success(slug, Activity.PATCH_APPLY);
		openPr(gh, slug, workdir, defaultBranch, branch, Activity.patchRequest(gh.forge()),
				props.botPrefix() + " Dependency patch upgrades",
				"Generated by advisor-loop with `advisor patch apply`.", dryRun);
	}

	private boolean startBranch(String slug, Path workdir, String branch) {
		try {
			git.setIdentity(workdir);
			git.checkoutNewBranch(workdir, branch);
			return true;
		}
		catch (Exception e) {
			log.warn("could not create branch {} on {}", branch, slug, e);
			return false;
		}
	}

	/** Commits, pushes and opens a PR when advisor actually changed something. */
	private void openPr(ForgeClient gh, String slug, Path workdir, String base, String head, String activity,
			String title, String body, boolean dryRun) {
		try {
			if (!git.hasChanges(workdir)) {
				reporter.skip(slug, activity);
				return;
			}
			git.addAll(workdir);
			git.commit(workdir, title);
			git.push(workdir, head, dryRun);
			gh.createPr(slug, base, head, title, body, dryRun);
			reporter.success(slug, activity);
		}
		catch (Exception e) {
			log.warn("{} failed for {}", activity, slug, e);
			reporter.fail(slug, activity);
		}
	}

	/**
	 * Decides whether this pass should leave the repository alone.
	 *
	 * <p>
	 * A single open bot PR younger than {@code advisor.staleness} is enough to skip,
	 * regardless of whether it is an upgrade or a patch PR. Only when every bot PR is
	 * stale are they deleted, so that this pass can recreate them; a stale PR is never
	 * deleted while a fresh one stands, which would otherwise throw away a PR that this
	 * pass has already decided not to replace.
	 * @return true when the repository should be skipped
	 */
	private boolean hasFreshPr(ForgeClient gh, String slug, boolean dryRun) {
		List<PrSummary> prs = gh.listOpenPrsByPrefix(slug, props.botPrefix());
		if (prs.stream().anyMatch(pr -> ageOf(pr).compareTo(props.staleness()) < 0)) {
			return true;
		}
		for (PrSummary pr : prs) {
			try {
				gh.closePr(slug, pr.number(), dryRun);
				gh.deleteBranch(slug, pr.headRefName(), dryRun);
				reporter.success(slug, Activity.staleRequestDelete(gh.forge()));
			}
			catch (Exception e) {
				log.warn("could not delete stale PR {}#{}", slug, pr.number(), e);
				reporter.fail(slug, Activity.staleRequestDelete(gh.forge()));
				return true; // could not clear it, so do not open a duplicate alongside
								// it
			}
		}
		return false;
	}

	private Path workdirFor(String slug) {
		String[] parts = slug.split("/", 2);
		if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
			return null;
		return props.workspace().resolve(parts[0]).resolve(parts[1]);
	}

	private Duration ageOf(PrSummary pr) {
		return pr.createdAt() == null ? Duration.ZERO : Duration.between(pr.createdAt(), clock.instant());
	}

	private static boolean hasBuildFile(Path repoDir) {
		return Files.exists(repoDir.resolve("pom.xml")) || Files.exists(repoDir.resolve("build.gradle"))
				|| Files.exists(repoDir.resolve("build.gradle.kts"));
	}

}
