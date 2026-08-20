package dev.dashaun.advisorloop.gitlab;

import dev.dashaun.advisorloop.forge.Forge;
import dev.dashaun.advisorloop.forge.model.PrSummary;
import dev.dashaun.advisorloop.forge.model.RepoSummary;
import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.process.CommandRunner;
import dev.dashaun.advisorloop.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The GitLab path cannot be exercised against a live instance here, so these tests pin
 * the exact argv handed to {@code glab} and the exact REST shapes parsed back.
 */
class GlabClientTest {

	private CommandRunner runner;

	private GlabClient client;

	@BeforeEach
	void setUp() {
		runner = mock(CommandRunner.class);
		client = new GlabClient(runner, TestProperties.at(Path.of("/tmp/ws")));
	}

	private void respond(String stdout) {
		when(runner.run(any(), any(Duration.class), anyList()))
			.thenReturn(new CommandResult(0, stdout, "", Duration.ZERO));
	}

	@SuppressWarnings("unchecked")
	private List<String> capturedArgv() {
		ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
		verify(runner).run(any(), any(Duration.class), argv.capture());
		return argv.getValue();
	}

	@Test
	void it_identifies_as_gitlab_and_builds_https_clone_urls() {
		assertThat(client.forge()).isEqualTo(Forge.GITLAB);
		assertThat(client.host()).isEqualTo("gitlab.com");
		assertThat(client.cliBinary()).isEqualTo("glab");
		assertThat(client.cloneUrl("grp/sub/proj")).isEqualTo("https://gitlab.com/grp/sub/proj.git");
	}

	@Test
	void listing_a_group_asks_for_projects_including_subgroups() {
		respond("[]");

		client.listRepos("my-group");

		List<String> argv = capturedArgv();
		assertThat(argv).startsWith("glab", "api", "--hostname", "gitlab.com", "--method", "GET");
		assertThat(argv).contains("--paginate");
		assertThat(argv.get(argv.size() - 1))
			.isEqualTo("groups/my-group/projects?per_page=100&archived=false&include_subgroups=true");
	}

	@Test
	void a_nested_group_path_is_url_encoded() {
		respond("[]");

		client.listRepos("parent/child");

		assertThat(argvLast()).startsWith("groups/parent%2Fchild/projects");
	}

	@Test
	void it_maps_the_gitlab_project_shape() {
		respond("""
				[{"path":"widget","path_with_namespace":"grp/widget","default_branch":"trunk",
				  "archived":false,"visibility":"private"},
				 {"path":"forked","path_with_namespace":"grp/forked","default_branch":"main",
				  "archived":true,"visibility":"public","forked_from_project":{"id":7}}]
				""");

		List<RepoSummary> repos = client.listRepos("grp");

		assertThat(repos).hasSize(2);
		assertThat(repos.get(0).nameWithOwner()).isEqualTo("grp/widget");
		assertThat(repos.get(0).defaultBranch()).isEqualTo("trunk");
		assertThat(repos.get(0).isPrivate()).isTrue();
		assertThat(repos.get(0).isFork()).isFalse();
		assertThat(repos.get(1).isFork()).as("forked_from_project marks a fork").isTrue();
		assertThat(repos.get(1).isArchived()).isTrue();
		assertThat(repos.get(1).isPrivate()).isFalse();
	}

	@Test
	void a_project_without_a_default_branch_falls_back() {
		// An empty GitLab project reports default_branch: null.
		respond("""
				[{"path":"empty","path_with_namespace":"grp/empty","default_branch":null,
				  "archived":false,"visibility":"public"}]
				""");

		assertThat(client.listRepos("grp").get(0).defaultBranch()).isEqualTo("main");
	}

	@Test
	void paginated_output_of_several_arrays_is_flattened() {
		// --paginate emits one JSON document per page; page two must not be dropped.
		respond("""
				[{"path":"a","path_with_namespace":"grp/a","visibility":"public"}]
				[{"path":"b","path_with_namespace":"grp/b","visibility":"public"}]
				""");

		assertThat(client.listRepos("grp")).extracting(RepoSummary::nameWithOwner).containsExactly("grp/a", "grp/b");
	}

	@Test
	void newline_delimited_objects_are_also_understood() {
		respond("""
				{"path":"a","path_with_namespace":"grp/a","visibility":"public"}
				{"path":"b","path_with_namespace":"grp/b","visibility":"public"}
				""");

		assertThat(client.listRepos("grp")).hasSize(2);
	}

	@Test
	void merge_requests_map_onto_the_shared_pr_model_by_iid() {
		respond("""
				[{"iid":42,"title":"[AdvisorBot] Dependency patch upgrades",
				  "web_url":"https://gitlab.com/grp/p/-/merge_requests/42",
				  "source_branch":"advisorbot/patch-1","target_branch":"main",
				  "created_at":"2026-08-19T10:00:00.000Z","state":"opened"},
				 {"iid":43,"title":"human MR","web_url":"u","source_branch":"x",
				  "target_branch":"main","created_at":"2026-08-19T10:00:00.000Z","state":"opened"}]
				""");

		List<PrSummary> prs = client.listOpenPrsByPrefix("grp/p", "[AdvisorBot]");

		assertThat(prs).hasSize(1);
		PrSummary pr = prs.get(0);
		assertThat(pr.number()).as("iid, not the global id, addresses an MR").isEqualTo(42);
		assertThat(pr.headRefName()).isEqualTo("advisorbot/patch-1");
		assertThat(pr.baseRefName()).isEqualTo("main");
		assertThat(pr.createdAt()).isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
	}

	@Test
	void creating_a_merge_request_posts_raw_string_fields() {
		respond("""
				{"iid":7,"web_url":"https://gitlab.com/grp/p/-/merge_requests/7"}
				""");

		String url = client.createPr("grp/p", "main", "advisorbot/patch-1", "[AdvisorBot] Dependency patch upgrades",
				"body", false);

		assertThat(url).isEqualTo("https://gitlab.com/grp/p/-/merge_requests/7");
		List<String> argv = capturedArgv();
		assertThat(argv).containsSequence("--method", "POST");
		// glab inverts gh's flags: --raw-field is the string form, so a title is never
		// retyped.
		assertThat(argv).containsSequence("--raw-field", "source_branch=advisorbot/patch-1");
		assertThat(argv).containsSequence("--raw-field", "target_branch=main");
		assertThat(argv).containsSequence("--raw-field", "title=[AdvisorBot] Dependency patch upgrades");
		assertThat(argv).doesNotContain("--field");
		assertThat(argv.get(argv.size() - 1)).isEqualTo("projects/grp%2Fp/merge_requests");
	}

	@Test
	void closing_a_merge_request_uses_the_state_event() {
		respond("{}");

		client.closePr("grp/p", 42, false);

		List<String> argv = capturedArgv();
		assertThat(argv).containsSequence("--method", "PUT");
		assertThat(argv).containsSequence("--raw-field", "state_event=close");
		assertThat(argv.get(argv.size() - 1)).isEqualTo("projects/grp%2Fp/merge_requests/42");
	}

	@Test
	void deleting_a_branch_encodes_the_slash_in_the_branch_name() {
		respond("{}");

		client.deleteBranch("grp/p", "advisorbot/patch-20260819", false);

		List<String> argv = capturedArgv();
		assertThat(argv).containsSequence("--method", "DELETE");
		assertThat(argv.get(argv.size() - 1))
			.isEqualTo("projects/grp%2Fp/repository/branches/advisorbot%2Fpatch-20260819");
	}

	@Test
	void dry_run_never_reaches_glab() {
		client.closePr("grp/p", 1, true);
		client.deleteBranch("grp/p", "b", true);
		assertThat(client.createPr("grp/p", "main", "h", "t", "b", true)).startsWith("dry-run://");

		verify(runner, org.mockito.Mockito.never()).run(any(), any(Duration.class), anyList());
	}

	@Test
	void a_failed_merge_request_creation_is_not_silently_swallowed() {
		when(runner.run(any(), any(Duration.class), anyList()))
			.thenReturn(new CommandResult(1, "", "403 Forbidden", Duration.ZERO));

		assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> client.createPr("grp/p", "main", "h", "t", "b", false)))
			.hasMessageContaining("403");
	}

	@Test
	void a_failed_listing_of_merge_requests_degrades_to_empty() {
		when(runner.run(any(), any(Duration.class), anyList()))
			.thenReturn(new CommandResult(1, "", "boom", Duration.ZERO));

		// A repo whose MRs cannot be read must not abort the pass.
		assertThat(client.listOpenPrsByPrefix("grp/p", "[AdvisorBot]")).isEmpty();
	}

	@Test
	void auth_status_shells_out_to_glab() {
		respond("");

		assertThat(client.authStatus()).isTrue();
		assertThat(capturedArgv()).containsExactly("glab", "auth", "status");
	}

	@Test
	void auth_status_is_false_when_glab_is_not_logged_in() {
		when(runner.run(any(), any(Duration.class), anyList()))
			.thenReturn(new CommandResult(1, "", "not authenticated", Duration.ZERO));

		assertThat(client.authStatus()).isFalse();
	}

	private String argvLast() {
		List<String> argv = capturedArgv();
		return argv.get(argv.size() - 1);
	}

}
