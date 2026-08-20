package dev.dashaun.advisorloop.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.forge.Forge;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.forge.model.PrSummary;
import dev.dashaun.advisorloop.forge.model.RepoSummary;
import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.process.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * GitLab support, driven through {@code glab}.
 *
 * <p>
 * Everything goes through {@code glab api} rather than the porcelain commands. The
 * porcelain output format varies between glab versions and some of it prompts
 * interactively, whereas the v4 REST shapes are stable and documented. Note that glab's
 * field flags are the reverse of gh's: {@code -f/--raw-field} is the string form,
 * {@code -F/--field} infers types.
 */
@Component
public class GlabClient implements ForgeClient {

	private static final Logger log = LoggerFactory.getLogger(GlabClient.class);

	private final CommandRunner runner;

	private final AdvisorProperties props;

	private final ObjectMapper mapper = JsonMapper.builder()
		.addModule(new JavaTimeModule())
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		.build();

	public GlabClient(CommandRunner runner, AdvisorProperties props) {
		this.runner = runner;
		this.props = props;
	}

	@Override
	public Forge forge() {
		return Forge.GITLAB;
	}

	@Override
	public String cloneUrl(String slug) {
		return "https://" + host() + "/" + slug + ".git";
	}

	@Override
	public String host() {
		return props.gitlab().host();
	}

	@Override
	public String cliBinary() {
		return props.gitlab().binary();
	}

	@Override
	public boolean authStatus() {
		return runner.run(null, timeout(), List.of(cliBinary(), "auth", "status")).ok();
	}

	@Override
	public List<RepoSummary> listRepos(String namespace) {
		String path = "groups/" + encode(namespace) + "/projects" + "?per_page=100&archived=false"
				+ "&include_subgroups=" + props.gitlab().includeSubgroups();
		CommandResult r = api(true, "GET", path);
		if (!r.ok()) {
			throw new IllegalStateException("glab api " + path + " failed: " + r.shortStderr());
		}
		List<RawProject> raw = readMany(r.stdout(), RawProject.class);
		List<RepoSummary> out = new ArrayList<>(raw.size());
		for (RawProject p : raw) {
			if (p.pathWithNamespace() == null)
				continue;
			out.add(new RepoSummary(p.path(), p.pathWithNamespace(),
					p.defaultBranch() == null || p.defaultBranch().isBlank() ? props.defaultBranchFallback()
							: p.defaultBranch(),
					p.archived(), p.forkedFromProject() != null, !"public".equalsIgnoreCase(p.visibility())));
		}
		return out;
	}

	@Override
	public String getDefaultBranch(String slug) {
		CommandResult r = api(false, "GET", "projects/" + encode(slug));
		if (!r.ok())
			return props.defaultBranchFallback();
		List<RawProject> one = readMany(r.stdout(), RawProject.class);
		if (one.isEmpty() || one.get(0).defaultBranch() == null || one.get(0).defaultBranch().isBlank()) {
			return props.defaultBranchFallback();
		}
		return one.get(0).defaultBranch();
	}

	@Override
	public List<PrSummary> listOpenPrsByPrefix(String slug, String titlePrefix) {
		CommandResult r = api(true, "GET", "projects/" + encode(slug) + "/merge_requests?state=opened&per_page=100");
		if (!r.ok()) {
			log.warn("glab merge_requests list failed for {}: {}", slug, r.shortStderr());
			return List.of();
		}
		return readMany(r.stdout(), RawMergeRequest.class).stream()
			.filter(mr -> mr.title() != null && mr.title().startsWith(titlePrefix))
			.map(mr -> new PrSummary(mr.iid(), mr.title(), mr.webUrl(), mr.sourceBranch(), mr.targetBranch(),
					mr.createdAt(), mr.state()))
			.toList();
	}

	@Override
	public void closePr(String slug, int number, boolean dryRun) {
		if (dryRun) {
			log.info("[dry-run] close MR {}!{}", slug, number);
			return;
		}
		CommandResult r = api(false, "PUT", "projects/" + encode(slug) + "/merge_requests/" + number,
				"state_event=close");
		if (!r.ok()) {
			throw new IllegalStateException("could not close MR " + slug + "!" + number + ": " + r.shortStderr());
		}
	}

	/**
	 * Best effort: the branch may already be gone, which is not worth failing the repo
	 * over.
	 */
	@Override
	public void deleteBranch(String slug, String branch, boolean dryRun) {
		if (dryRun) {
			log.info("[dry-run] delete branch {} on {}", branch, slug);
			return;
		}
		api(false, "DELETE", "projects/" + encode(slug) + "/repository/branches/" + encode(branch));
	}

	@Override
	public String createPr(String slug, String base, String head, String title, String body, boolean dryRun) {
		if (dryRun) {
			log.info("[dry-run] create MR on {} title={}", slug, title);
			return "dry-run://mr/" + slug;
		}
		CommandResult r = api(false, "POST", "projects/" + encode(slug) + "/merge_requests", "source_branch=" + head,
				"target_branch=" + base, "title=" + title, "description=" + body);
		if (!r.ok()) {
			throw new IllegalStateException("could not create MR on " + slug + ": " + r.shortStderr());
		}
		List<RawMergeRequest> created = readMany(r.stdout(), RawMergeRequest.class);
		return created.isEmpty() || created.get(0).webUrl() == null ? "" : created.get(0).webUrl();
	}

	/**
	 * Builds a {@code glab api} invocation. Fields are passed with {@code -f} (raw
	 * string) so a title or branch name is never reinterpreted as a number, boolean, or
	 * JSON.
	 */
	private CommandResult api(boolean paginate, String method, String path, String... fields) {
		List<String> argv = new ArrayList<>();
		argv.add(cliBinary());
		argv.add("api");
		argv.add("--hostname");
		argv.add(host());
		argv.add("--method");
		argv.add(method);
		if (paginate) {
			argv.add("--paginate");
		}
		for (String f : fields) {
			argv.add("--raw-field");
			argv.add(f);
		}
		argv.add(path);
		return runner.run(null, timeout(), argv);
	}

	/**
	 * Reads however glab chose to frame the response: one array, several concatenated
	 * arrays from {@code --paginate}, or newline-delimited objects. Anything else would
	 * break on page two.
	 */
	<T> List<T> readMany(String json, Class<T> type) {
		List<T> out = new ArrayList<>();
		if (json == null || json.isBlank())
			return out;
		try (JsonParser parser = mapper.getFactory().createParser(json)) {
			while (true) {
				JsonNode node = mapper.readTree(parser);
				if (node == null || node.isMissingNode())
					break;
				if (node.isArray()) {
					for (JsonNode child : node)
						out.add(mapper.treeToValue(child, type));
				}
				else if (node.isObject()) {
					out.add(mapper.treeToValue(node, type));
				}
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to parse glab JSON: " + e.getMessage(), e);
		}
		return out;
	}

	/**
	 * GitLab addresses projects and branches by URL-encoded path, so slashes must become
	 * %2F.
	 */
	static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private Duration timeout() {
		return props.process().timeoutPerCommand();
	}

	private record RawProject(String path, @JsonProperty("path_with_namespace") String pathWithNamespace,
			@JsonProperty("default_branch") String defaultBranch, boolean archived, String visibility,
			@JsonProperty("forked_from_project") JsonNode forkedFromProject) {
	}

	private record RawMergeRequest(int iid, String title, @JsonProperty("web_url") String webUrl,
			@JsonProperty("source_branch") String sourceBranch, @JsonProperty("target_branch") String targetBranch,
			@JsonProperty("created_at") Instant createdAt, String state) {
	}

}
