# advisor-loop

Walks every repository in a set of GitHub organizations and GitLab groups once, runs [Tanzu Application Advisor](https://techdocs.broadcom.com/us/en/vmware-tanzu/spring/application-advisor/1-6/app-advisor/index.html) 1.6.7 against each Maven or Gradle project, opens pull requests for what it finds, and stops.

The output is the whole user interface: `Org/Repo - activity - status`, one line per activity,
nothing else on the console.

```
$ java -jar target/advisor-loop.jar --groups=dashaun-live

ORG/REPO                                             ACTIVITY             STATUS
---------------------------------------------------- -------------------- ------
dashaun-live/live.dashaun.service.config             build-config get     success
dashaun-live/live.dashaun.service.config             upgrade-plan get     success
dashaun-live/live.dashaun.service.config             apply patch          success
dashaun-live/live.dashaun.service.config             patch MR             success
dashaun-live/live.dashaun.gateway.routing            build-config get     success
dashaun-live/live.dashaun.gateway.routing            upgrade-plan get     success
dashaun-live/live.dashaun.gateway.routing            apply patch          success
dashaun-live/live.dashaun.gateway.routing            patch MR             success
dashaun-live/live.dashaun.mvc.hello                  fresh MR             skip

11 success, 1 fail, 1 skip
```

A repository with no build file produces no lines at all, and neither does an empty one. `fresh MR`
/ `fresh PR` means an existing bot request is under 24 hours old, so the repository was skipped
before advisor ran. Statuses are only ever `success`, `fail`, or `skip`.

## Running

```sh
export JAVA_HOME=$(sdk home java 21.0.11-librca)
mvn package

java -jar target/advisor-loop.jar                     # namespaces from application.yml
java -jar target/advisor-loop.jar --orgs=foo,bar      # GitHub organizations
java -jar target/advisor-loop.jar --groups=x,y        # GitLab groups
java -jar target/advisor-loop.jar --orgs=a --groups=b # both forges in one pass
java -jar target/advisor-loop.jar --repos=one,two     # restrict to named repositories
java -jar target/advisor-loop.jar --staleness=1h      # recreate bot PRs older than 1h (default 24h)
java -jar target/advisor-loop.jar --dry-run           # clone and analyse, never push or open anything
```

`--repos` accepts either the bare repository name or the full `namespace/name` slug, case-insensitive,
and works on both forges. It is the way to inspect what the bot produces on one repository before
turning it loose on a whole namespace:

```sh
java -jar target/advisor-loop.jar --groups=my-group --repos=my-service --dry-run
```

Exit codes: `0` clean pass, `1` the pass completed with at least one failed activity, `2` the pass could not start (nothing configured to crawl, a forge CLI missing or unauthenticated, or another run holds the lock).

Prerequisites: `advisor` 1.6.7 and `git` on `PATH`, plus the CLI for each forge you target — `gh auth login` for GitHub, `glab auth login` for GitLab (verified against glab 1.114.0). Only the forges actually in the run are checked, so GitHub-only users never need `glab`.

A CLI that is missing entirely is reported the same way as one that is not logged in: the pass stops
with exit 2 and a message naming the binary. It is never a stack trace — `ProcessBuilderCommandRunner`
returns exit 127 for a binary it cannot start, the way a shell does.

## GitLab

GitLab support is symmetric with GitHub: same state machine, same `[AdvisorBot]` prefix, same 24-hour staleness rule. Merge requests are called MRs in the output, so you see `upgrade MR` and `patch MR` instead of `upgrade PR`.

```yaml
advisor:
  gitlab:
    groups: [my-group]        # or --groups=my-group
    host: gitlab.com          # set to your instance for self-hosted
    binary: glab
    include-subgroups: true   # descend into subgroups
```

Everything goes through `glab api` against GitLab's v4 REST API rather than the porcelain commands, because the porcelain output format varies between glab versions and some of it prompts interactively. Self-hosted instances work by setting `advisor.gitlab.host`; it is threaded through the clone URLs, the `glab --hostname` flag, and the git credential helper.

## What it does per repository

1. Clone or hard-reset the repo to its default branch under `.workspace/<org>/<repo>`.
2. Skip it unless it has a `pom.xml`, `build.gradle`, or `build.gradle.kts`.
3. If any `[AdvisorBot]` PR is **newer than 24 hours**, skip the repository entirely — an upgrade PR or a patch PR, either one is enough. Otherwise delete every stale bot PR so this pass can recreate it.
4. `advisor build-config get`, then resolve an upgrade plan (see below).
5. If a plan exists, apply it on `advisorbot/upgrade-<ts>` and open an **upgrade PR**.
6. Reset to the default branch, run `advisor patch apply` on `advisorbot/patch-<ts>`, and open a **patch PR**.

The upgrade and patch PRs are branched independently off the default branch, so they never stack, and "no upgrade available" does not prevent a patch PR.

## The mapping loop

Mappings live on the local filesystem at `.workspace/mappings` — there is no mappings git repo and no mapping PRs. Advisor is pointed at that folder for every invocation:

```
SPRING_ADVISOR_MAPPING_CUSTOM_0_FILEPATH=<abs>/.workspace/mappings
SPRING_ADVISOR_MAPPING_CUSTOM_0_MERGE_STRATEGY=override
```

`override` layers these mappings on top of advisor's built-in ones rather than replacing them, which is what keeps a generated mapping from colliding with one advisor already ships.

Mappings are generated **one at a time**, and `upgrade-plan get` is both the driver and the validator:

```
upgrade-plan get
  ├── plan produced ................ done, the previous mapping is proven good
  ├── needs mapping for X .......... advisor mapping create -c X, install it, loop
  ├── asks for X again ............. the mapping did not help -> roll it back, move on
  └── complains about the mappings . roll back the newest mapping, move on
```

**What gets stored.** `advisor mapping create -c X` does not return a mapping for X — it returns the
whole project X belongs to, as seen through X. Ask about `spring-boot-grpc-server` and you get the
spring-boot project with only the coordinates that co-occur with grpc-server and only the two
versions that artifact has; ask via `spring-boot` and you get twenty-two. One project produced thirty
such files, all of them containing `org.springframework.boot:spring-boot`.

Measured across a real 65-file store, those generated `rewrite` graphs contain **zero recipes** —
and recipes are what actually rewrite a POM. Storing them therefore gains nothing and *destroys*
advisor's built-in recipes for that project, because `override` replaces the block. The failure is
silent: the plan still looks right while `upgrade-plan apply` produces no diff and no pull request.

So the store keeps `slug` + the **union of coordinates** + an **empty** `rewrite`:

```json
{ "slug": "spring-boot",
  "coordinates": [ "org.springframework.boot:spring-boot-grpc-server", "..." ],
  "rewrite": {} }
```

The `rewrite` key must be present — omitting it fails validation with `Failed to load the mapping
source` — but empty, so advisor's own version graph and recipes survive. What advisor genuinely
lacks is the coordinate list (the Boot 4.x module split), and that is what we supply. A second
generation for the same slug merges into the same file, unioning coordinates.

**Budget.** Advisor names *every* unmappable dependency in its first answer — 46 of them for one real
Spring Boot 4.1 + Spring AI project — so a repository's turn is budgeted at exactly that count. The
number is finite and known up front, so the loop cannot run away, and one pass can work through the
whole set instead of nibbling at it.

**Limitation.** This works for projects advisor already ships a mapping for: the built-in version
graph and recipes survive, and the upgrade applies. For a project advisor has never heard of, the
coordinate is recognised and advisor stops asking, but there is no version graph to act on, so a plan
appears while `upgrade-plan apply` produces no diff and the PR is skipped. Seen with
`net.logstash.logback:logstash-logback-encoder`.

**The store is global.** Mappings apply to every repository on both forges, so one repository's turn
changes what every later repository sees. That is the point — a GitLab project took its upgrade MR
using mappings generated entirely from GitHub repositories — but it does mean a bad mapping has reach
beyond the repository that created it. The rollback path is the guard.

## Workspace layout

```
.workspace/
  .lock                  # single-instance guard
  mappings/              # the local mapping store advisor reads
  .mapping-work/         # scratch dir for `advisor mapping create`
  <org>/<repo>/          # one persistent clone per repository
```

Everything is relative to the working directory, so `.workspace` lands next to the jar you ran from.

## Repository hygiene

Clones use HTTPS, and each clone delegates its credentials to the forge's own CLI
(`gh auth git-credential` or `glab auth git-credential`) so a push works even on a machine set up
only for SSH. That is set on the clone, never in your global git config.

Advisor writes `.advisor/` into the repository root and drives Maven/Gradle into `target/`/`build/`.
None of that belongs in a pull request, and while untracked it also makes the repo look dirty when
advisor changed nothing. After every refresh, advisor-loop registers those paths in the clone's
`.git/info/exclude` — local to the clone, never committed. Git excludes apply only to untracked
files, so a repository that genuinely tracks one of those paths still reports its changes normally.

That last point has a sharp edge worth knowing about. A repository that **commits a generated file**
will see it rewritten when advisor runs the build, and because the file is tracked the excludes do
not suppress it, so the change rides along into the pull request. Observed in the wild with a
`.git-versioned-pom.xml` produced by a git-versioning Maven extension: the bot's MR carried an
unrelated `<version>0.0.1-SNAPSHOT</version>` → `<version><commit-sha></version>` hunk beside the
real dependency bumps. The fix belongs in the repository — such files should be gitignored, not
committed — since suppressing tracked files here would also hide legitimate upgrade edits.

## Configuration

All settings live under `advisor:` in `src/main/resources/application.yml` and can be overridden on the command line, e.g. `--advisor.staleness=PT6H`.

| Key | Default | Meaning |
| --- | --- | --- |
| `orgs` | `[dashaun-demo]` | GitHub organizations to crawl |
| `gitlab.groups` | `[]` | GitLab groups to crawl |
| `gitlab.host` | `gitlab.com` | GitLab instance, for self-hosted |
| `gitlab.include-subgroups` | `true` | Descend into GitLab subgroups |
| `gitlab.binary` | `glab` | GitLab CLI to shell out to |
| `github-host` | `github.com` | GitHub host used for clone URLs and credentials |
| `gh-binary` | `gh` | GitHub CLI to shell out to |
| `default-branch-fallback` | `main` | Used only when the forge does not report a default branch |
| `staleness` | `PT24H` | Age at which a bot PR is deleted and recreated. Override per run with `--staleness=1h` (plain or ISO-8601; `0s` forces every bot PR to be recreated) |
| `workspace` | `${user.dir}/.workspace` | Root of all work |
| `bot-prefix` | `[AdvisorBot]` | Title prefix identifying this bot's PRs |
| `skip-archived` | `true` | Ignore archived repos |
| `skip-forks` | `false` | `dashaun-demo` is entirely forks, so forks are in scope |
| `mappings.merge-strategy` | `override` | Advisor merge strategy for the local store |
| `error-rules` | see yaml | Regexes mapping advisor error text to an action |

`error-rules` is the part most likely to need tuning: it turns advisor's error text into either `MISSING_MAPPING` (with the coordinate to generate) or `BAD_MAPPING` (roll back). New advisor phrasings can be handled by adding a regex, without touching code.

## Troubleshooting

The activity table owns the console, so there is no console logging at all. Everything diagnostic —
including the `git`/`gh`/`advisor` stderr behind any `fail` — goes to:

```
.workspace/advisor-loop.log        # override the path with ADVISOR_LOOP_LOG
```

When an activity reports `fail`, grep that file for the repository name to see the cause.

## Re-running after failures

Re-run the same namespace. That is an efficient retry: a repository with any bot PR under 24 hours
old is skipped *before* advisor runs, so a re-run only pays for the repositories that still need
something. Failures from the previous pass have no PR, so they are retried in full. Use `--repos` to
narrow the retry to a single repository.

**Pushes fail transiently.** Both forges have rejected a push once and then accepted the identical
push on the next pass with nothing changed in between — GitHub with a plain failure, GitLab with
`pre-receive hook declined`. There is no retry in the code today, so a transient failure costs that
repository its PR until the next pass. If you see a lone `patch PR fail` or `patch MR fail`, re-run
that repository before hunting for a cause:

```sh
java -jar target/advisor-loop.jar --groups=my-group --repos=the-one-that-failed
```

## Notes on advisor 1.6.7

Two CLI changes from earlier releases are baked in:

- `advisor mapping build` is now `advisor mapping create`.
- `advisor upgrade-plan apply` no longer takes `--step 1`; it applies the first step by default.
