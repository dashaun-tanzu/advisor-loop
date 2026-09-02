# AGENTS.md

Technical guidance for coding agents (and humans reading the internals) working in this repository.
`README.md` is the user-facing document: what the tool does, how to run it, and what to configure.
Everything here is the *why* behind the code — the observed behaviour of `advisor` 1.6.7, the traps in
its output, and the decisions that must not be undone.

## Commands

Targets Java 21; the machine default is 17. Note that exporting `JAVA_HOME` does **not** change which
`java` binary is on `PATH` — invoke the 21 binary directly when running the jar.

```sh
export JAVA_HOME=$(sdk home java 21.0.11-librca)
mvn package
mvn test

# Sources follow spring-javaformat, but the plugin is deliberately not in the pom, so a build
# never rewrites your tree. Apply it on demand with the fully-qualified coordinates:
mvn io.spring.javaformat:spring-javaformat-maven-plugin:0.0.48:apply
mvn io.spring.javaformat:spring-javaformat-maven-plugin:0.0.48:validate
mvn -Dtest=RepoProcessorTest test
mvn -Dtest=RepoProcessorTest#a_missing_mapping_is_generated_and_then_validated_by_a_second_upgrade_plan_get test

$(sdk home java 21.0.11-librca)/bin/java -jar target/advisor-loop.jar --dry-run --orgs=dashaun-demo
$(sdk home java 21.0.11-librca)/bin/java -jar target/advisor-loop.jar --groups=dashaun-live --dry-run
# --repos narrows a pass to named repositories (bare name or full slug, either forge)
$(sdk home java 21.0.11-librca)/bin/java -jar target/advisor-loop.jar --groups=dashaun-live --repos=live.dashaun.mvc.hello
```

Exit codes: `0` clean, `1` pass completed with at least one failed activity, `2` pass could not start.

Prereqs at runtime: `advisor` 1.6.7 and `git` on `PATH`, plus the CLI for each forge in the run
(`gh auth login` for GitHub, `glab auth login` for GitLab). Only forges actually targeted are checked.

## Architecture

A single pass over GitHub organizations and GitLab groups, driven by shelled-out CLIs (`gh`, `glab`,
`git`, `advisor`) behind one `CommandRunner`. Layering, innermost first:

1. `process/` — `CommandRunner` and its only impl `ProcessBuilderCommandRunner` (virtual-thread
   stdout/stderr pumps, 1 MiB cap, `destroyForcibly` on timeout). **Unlike the predecessor project,
   `run` takes an `env` map**; that is how advisor is pointed at the local mapping store. Tests mock
   this layer or the typed clients above it.
2. `git/`, `forge/`, `github/`, `gitlab/`, `advisor/` — typed wrappers. `ForgeClient` is the whole
   contract the pipeline sees; nothing above it mentions GitHub or GitLab. `GhClient` parses
   `gh --json` with Jackson; `GlabClient` uses `glab api` against GitLab's v4 REST API (never the
   porcelain, whose output format drifts between versions and can prompt). **glab inverts gh's
   field flags**: `-f/--raw-field` is the string form, `-F/--field` infers types — always pass
   `--raw-field` so a title is never reinterpreted as a number or JSON.
   `AdvisorClient` is the only thing that reads `.advisor/errors/*` and decides `AdvisorOutcome`.
   `AdvisorErrorClassifier` is config-driven by `advisor.error-rules` and emits a sealed `ErrorKind`.
3. `mapping/` — `MappingStore`, the local filesystem store. No git, no PRs.
4. `pipeline/` — `RepoProcessor` is the heart and takes a `ForgeClient` per repository;
   `NamespaceProcessor` iterates one org/group; `Pipeline` resolves `Target`s (forge + namespace),
   holds the `LockService` file lock, and runs exactly one pass. Only the forges a pass actually
   touches are authentication-checked, so a GitHub-only run never needs `glab`.
5. `ui/` — `ActivityReporter` is the entire user interface.

### The per-repo state machine (`RepoProcessor.process`)

```
preflight   git ensureFresh -> clone, or fetch + checkout + reset --hard + clean -fdx
(a) gate    skip silently unless pom.xml / build.gradle[.kts]
(b) PRs     any open [AdvisorBot] PR younger than advisor.staleness (24h) -> skip the whole
              repository, upgrade and patch alike, before advisor runs
            otherwise every bot PR is stale -> close + delete branch, then recreate below
(c) plan    build-config get, then the mapping loop below
(d) upgrade branch advisorbot/upgrade-<ts>, upgrade-plan apply, commit/push/PR
(e) patch   reset to default branch, branch advisorbot/patch-<ts>, patch apply, commit/push/PR
```

Steps (d) and (e) are independent: each branches off the default branch, so the PRs never stack, and
"no upgrade available" still allows a patch PR.

The fresh-PR check is repo-wide on purpose. An earlier version blocked only the matching kind, which
almost never short-circuited anything: a repo with no upgrade available only ever has a patch PR, so
the upgrade kind was never blocked and every re-run still paid full advisor cost. Skipping on *any*
fresh PR is what makes a re-run cheap, since advisor is minutes per repo and everything else seconds.
A stale PR is deliberately not deleted while a fresh one stands — that would discard a PR the same
pass has already decided not to replace.

### How advisor actually reports a missing mapping (verified 2026-08-19)

**Not as an error.** With an unmappable dependency, `upgrade-plan get` exits **0**, writes **no**
`.advisor/errors/` file, and prints this on stdout:

```
Please request your administrator to configure the projects of the following dependencies:
	- io.pivotal.spring.cloud:cloudfoundry-certificate-truster
		uses:
			- spring-framework
		blocking upgrades for:
			- spring-boot
In order to learn more about publishing upgrade mappings, visit https://...
No upgrade plans available - your project seems to be up to date.
```

Two traps live in that output, and the code exists to survive both:

1. It is the **success** path, so `AdvisorRunResult.errored()` is false and `AdvisorErrorClassifier`
   never sees it. `UnmappedDependencyScanner` reads the success output instead. The
   `MISSING_MAPPING` error-rules were inherited from the predecessor and match text 1.6.7 never
   emits; they are kept only in case an error-path variant exists.
2. It ends with **"No upgrade plans available"**, so a repository blocked by a missing mapping is
   otherwise indistinguishable from one that is genuinely up to date. Coordinates must be extracted
   *before* that phrase is believed — which is why the scan happens ahead of the `noUpgrade()`
   check.

Only bullets carrying a `groupId:artifactId` are coordinates; the nested `uses:` and
`blocking upgrades for:` bullets are bare project names and are skipped by construction.

Also verified: pointing `SPRING_ADVISOR_MAPPING_CUSTOM_0_FILEPATH` at an **empty** folder is a hard
error (`The mapping source is empty`, exit 1), which is why `AdvisorEnv` omits both variables until
the store holds at least one `.json`. And a broken mapping surfaces as
`Failed to load an additional upgrade mapping from '<path>'`, which the `BAD_MAPPING` rules match.

### The mapping loop (the subtle part)

Mappings live at `<workspace>/mappings` and are handed to advisor through the environment:

```
SPRING_ADVISOR_MAPPING_CUSTOM_0_FILEPATH=<abs path to the folder>
SPRING_ADVISOR_MAPPING_CUSTOM_0_MERGE_STRATEGY=override
```

`override` is deliberate and set from the start: it layers our mappings on top of advisor's built-in
ones rather than replacing them, which is what prevents a generated mapping from colliding with a
shipped one. `AdvisorEnv` omits both vars when the store holds no `.json`, since pointing advisor at
an empty folder buys nothing.

Mappings are generated **one at a time**, and `upgrade-plan get` is both the driver and the validator
(the flowchart is in `README.md`):

- plan produced → done; any mapping added in the previous round is thereby proven good
- `MissingMapping(X)` → `advisor mapping create -c X`, install, loop (this is the only place mappings
  are created)
- same coordinate returns → the mapping did not help → roll it back, move on
- `BadMapping` → roll back the newest mapping, move on

**Budget.** Advisor names *every* unmappable dependency in its first answer — 46 of them for one real
Spring Boot 4.1 + Spring AI project — so a repository's turn is budgeted at exactly that count,
derived at runtime. There is no fixed `max-mapping-attempts` any more: a constant cap of 5 meant that
project needed ten separate runs, and the count is finite and known up front, so the loop still
cannot run away.

**What is stored is only the coordinates.** `advisor mapping create -c X` does not return a mapping
for X; it returns the whole project X belongs to, *as seen through X*. Ask about
`spring-boot-grpc-server` and you get the spring-boot project with just the coordinates that
co-occur with grpc-server and only the two versions that artifact has; ask via `spring-boot` and you
get twenty-two versions. One project produced thirty such files, every one of them containing
`org.springframework.boot:spring-boot`.

Three measurements against that real 65-file store decide the storage form:

- the generated `rewrite` graphs contain **zero recipes** — none, across every file. Recipes are what
  actually rewrite a POM, and advisor ships them for projects it knows.
- keeping our `rewrite` therefore **destroys** those built-in recipes, because `override` replaces
  the block for that slug. The symptom is silent and nasty: the plan still reads correctly while
  `upgrade-plan apply` produces no diff and no pull request.
- the graphs also contradict each other — of the twenty-two version keys shared across those thirty
  files, **none** agreed on their `requirements` — so there is no honest way to blend them.

So `MappingStore.asDelta` stores `slug` + the union of coordinates + an **empty** `rewrite`. The
block must be present (omitting it fails validation with `Failed to load the mapping source`) but
empty, leaving advisor's own graph and recipes intact. What advisor genuinely lacks is the coordinate
list — the Boot 4.x module split it does not yet enumerate — and that is exactly what we supply.

**Two approaches that were tried and are worse.** Do not reintroduce either:

- *Suffixing colliding slugs* (`spring-boot-1.json`, `-2`, ...). Advisor treats `slug` as project
  identity, so this fragmented spring-boot into 37 phantom projects; plans came back naming
  `spring-boot-28` instead of `spring-boot`, and apply produced nothing. Verified by A/B against an
  unrelated repo whose upgrade PR disappeared and returned once the store was rebuilt.
- *Overwriting on collision.* Each new mapping evicted the previous coordinate's coverage and the
  loop oscillated between clusters without accumulating.

`MappingStore` records the prior bytes of any file an addition overwrote, so `rollback` restores
exactly rather than guessing. That matters because merging is destructive to the previous content:
without the backup, rolling back a merge would lose the coordinates the earlier generation added.

### Known limitation: projects advisor has never heard of

Storing coordinates without a version graph works for a project advisor **already ships** a mapping
for — spring-boot keeps its built-in graph and recipes, and the upgrade applies. It does *not* work
for a project advisor has no knowledge of at all: the coordinate is recognised, advisor stops asking,
and a plan appears, but there is nothing to apply, so `upgrade PR` reports `skip`.

Observed on `dashaun-demo/logback-logstash-elastic-demo`, blocked by
`net.logstash.logback:logstash-logback-encoder` — a mapping of 1 coordinate and 0 versions.

This is not a regression (the repo had no upgrade before either), but it bounds what the mapping loop
can achieve. The untested refinement is to keep the generated `rewrite` when the slug is one advisor
does *not* already ship, and empty it when it does: a genuinely new slug cannot clobber a built-in,
since `override` has nothing to replace. The hard part is detecting "advisor already knows this
project" — a collision with our own store is not the same signal, and the first `spring-boot.json`
written has no collision yet would still wipe the built-in recipes.

## Conventions and gotchas

- **Authoritative rule:** after any `advisor` invocation the contents of `<repo>/.advisor/errors/`
  decide `ERRORED`, **not** the exit code. Empty errors dir plus non-zero exit synthesizes an error
  from stderr. Implemented in `AdvisorClient.classify`.
- **Real error-file shape** (observed on 1.6.7): `.advisor/errors/<yyyyMMdd-HHmmssSSS>.log`, whose
  first line is `Application Advisor CLI Version: 1.6.7` followed by a Java stack trace whose
  message line reads `com.vmware.tanzu.spring.advisor.exceptions.ControlledException: <message>`
  (e.g. `Could not process project dependencies` when a project's dependencies do not resolve).
  `advisor.error-rules` are matched with DOTALL against the whole file, so a pattern only has to
  match the message text, not the position. Note that `ErrorKind.Other`'s summary takes the *first*
  line, which for these files is the version banner — fine today because the summary is never
  displayed, but do not start relying on it.
- **advisor 1.6.7 API drift** from the predecessor project: `mapping build` is now `mapping create`,
  and `upgrade-plan apply` no longer accepts `--step 1` (it applies step one by default). Verify any
  new subcommand against `advisor <cmd> --help` before coding it; the techdocs lag the binary.
- **`advisor patch apply --push` is deliberately unused.** It would open its own PR via
  `GIT_TOKEN_FOR_PRS` and bypass the `[AdvisorBot]` title convention, so we commit and open PRs
  ourselves.
- **`.advisor/` is untracked repo-root junk.** Advisor writes `.advisor/` into the repository root
  and drives Maven/Gradle into `target/`/`build/`. Left alone, `git status --porcelain` reports
  `?? .advisor/`, which both makes `hasChanges()` true when advisor changed nothing (an empty PR)
  and sweeps scratch files into `git add -A`. `GitClient.excludeBuildArtifacts` writes these paths
  into the clone's `.git/info/exclude` after every `ensureFresh`. Excludes affect only *untracked*
  files, so a repo that genuinely tracks `target/` still reports edits to it — see `GitClientTest`.
- **The UI is the contract.** `ActivityReporter` prints `Org/Repo - activity - status` and nothing
  else. `logback-spring.xml` deliberately declares **no console appender**, so nothing can
  interleave with the table; all logging goes to `<workspace>/advisor-loop.log` at DEBUG. Never add
  `System.out` outside the reporter, and never reintroduce `logging.level.*` to application.yml —
  Boot applies those on top of logback and would silence the file log.
- **`skip-forks` defaults to `false`** — every repo in `dashaun-demo` is a fork, so `true` silently
  empties the run.
- **Bot identity** is an exact title prefix match on `[AdvisorBot]`; a human PR with that prefix would
  be treated as the bot's. Branch prefixes (`advisorbot/upgrade-`, `advisorbot/patch-`) distinguish
  the two PRs a repo can carry; there is deliberately no per-kind blocking any more.
- **`@SpringBootTest` runs the `ApplicationRunner`.** A context test with the real `advisor.orgs`
  would start a genuine, non-dry-run pass and open pull requests. `ApplicationContextTest` pins
  `advisor.orgs=` (empty), `advisor.dry-run=true`, and a throwaway `advisor.workspace`; keep all
  three on any new context test.
- **Failures must stay explainable.** `RepoProcessor` catches broadly so one repo cannot end the
  pass, but every catch logs at WARN with the exception. Without that the table shows `fail` with
  no recoverable cause, which is how a real push failure went undiagnosed once.
- **A missing CLI is not an exception.** `ProcessBuilderCommandRunner` returns exit 127 when a
  binary cannot start, the way a shell does. It used to throw, which escaped the `ApplicationRunner`
  and — because there is no console appender — exited silently with code 1 and no message at all.
  The runner also catches anything escaping `pipeline.run` and prints it to stderr.
- **GitLab identifiers**: an MR is addressed by its `iid` (per-project), never the global `id`, and
  project and branch paths are URL-encoded, so `advisorbot/patch-x` becomes `advisorbot%2Fpatch-x`.
- **Pushes fail transiently on both forges.** GitHub rejected one push outright and GitLab rejected
  one with `pre-receive hook declined`; both accepted the byte-identical push on the next pass with
  nothing changed in between. There is no retry in the code, so a transient failure costs that repo
  its PR until the next pass. Re-run with `--repos=<name>` before investigating a lone PR/MR failure.
  Adding a single push retry is the obvious improvement if this keeps happening.
- **A repo that commits a generated file will pollute its own PR.** Excludes only suppress
  *untracked* files, so a tracked-but-generated file (seen in the wild: `.git-versioned-pom.xml`
  from a git-versioning Maven extension) gets rewritten when advisor runs the build and rides along
  into the PR — the bot's MR carried an unrelated `<version>0.0.1-SNAPSHOT</version>` →
  `<version><commit-sha></version>` hunk beside the real dependency bumps. Do not "fix" this by
  filtering tracked paths — that would also hide legitimate upgrade edits. The fix belongs in the
  offending repository's `.gitignore`.
- **`Clock` is injected** so staleness and branch timestamps are testable; pass `Clock.fixed(...)`.
- **Never hardcode `main`** — resolve via `ForgeClient.getDefaultBranch` with
  `advisor.default-branch-fallback` as last resort.
- **Everything is relative to the working directory**: `advisor.workspace` is `${user.dir}/.workspace`.

## What has actually been exercised

Worth knowing before trusting any path here, because the unit tests cover more than production has.
Last measured 2026-08-20 across `dashaun-demo` (GitHub) and `dashaun-live` (GitLab): 139 activities,
0 failures, 0 rollbacks, store grown to 83 mappings.

| Path | GitHub | GitLab |
| --- | --- | --- |
| list / clone / advisor / `patch apply` | proven | proven |
| push + open PR/MR | proven | proven |
| stale-PR delete and recreate | proven | proven |
| `upgrade-plan apply` → upgrade PR/MR | proven | proven |
| `mapping create` + validation | proven | proven |
| `mapping rollback` | **never triggered** | **never triggered** |

`mapping rollback` is the last untested path on either forge: no generated mapping has yet failed
validation, so neither the "advisor asks for the coordinate again" branch nor the `BadMapping` branch
has run outside unit tests.

**The store is shared across forges, and that is load-bearing.** `dashaun-live/live.dashaun.service.config`
took its upgrade MR with zero `mapping create` calls, because 68 mappings generated from GitHub repos
already covered it. Mappings are global state: one repository's turn changes what every later
repository sees, on either forge.

**The mapping loop unblocks upgrades that were previously invisible.** Three `dashaun-demo`
repositories had reported patch-only for every prior run; with mappings they produced upgrade PRs
(`xyz.gofastforever.account` needed 24 of them). Those upgrades always existed — advisor was
reporting the blockage as "No upgrade plans available", which older code read as "nothing to do".

The one real advisor error observed is `ControlledException: Could not process project dependencies`,
correctly classified as `Other`. The `MISSING_MAPPING` error-rules have still never matched anything,
because 1.6.7 reports missing mappings on the success path instead — see above.

## Related

- `~/fun/dashaun-tanzu/spring-shell-advisor/` — the predecessor. Same domain, but a Spring Shell REPL
  that opened mapping *PRs* against a git repo and had no patch support. Useful for history only.
- `~/fun/dashaun-tanzu/advisor-mappings/` — the mapping git repo the predecessor used. This project
  deliberately does not touch it; mappings are local filesystem state.
- Patch/custom-upgrade docs:
  https://techdocs.broadcom.com/us/en/vmware-tanzu/spring/application-advisor/1-6/app-advisor/custom-upgrades.html
