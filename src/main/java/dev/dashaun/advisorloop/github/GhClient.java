package dev.dashaun.advisorloop.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.time.Duration;
import java.util.List;

@Component
public class GhClient implements ForgeClient {

	private static final Logger log = LoggerFactory.getLogger(GhClient.class);

	private final CommandRunner runner;

	private final AdvisorProperties props;

	private final ObjectMapper mapper = JsonMapper.builder()
		.addModule(new JavaTimeModule())
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		.build();

	public GhClient(CommandRunner runner, AdvisorProperties props) {
		this.runner = runner;
		this.props = props;
	}

	@Override
	public Forge forge() {
		return Forge.GITHUB;
	}

	@Override
	public String cloneUrl(String slug) {
		return "https://" + host() + "/" + slug + ".git";
	}

	@Override
	public String host() {
		return props.githubHost();
	}

	@Override
	public String cliBinary() {
		return props.ghBinary();
	}

	@Override
	public boolean authStatus() {
		return runner.run(null, timeout(), props.ghBinary(), "auth", "status").ok();
	}

	@Override
	public List<RepoSummary> listRepos(String org) {
		CommandResult r = runner.runOrThrow(null, timeout(), props.ghBinary(), "repo", "list", org, "--limit", "1000",
				"--json", "name,nameWithOwner,defaultBranchRef,isArchived,isFork,isPrivate");
		List<RawRepo> raw = readJson(r.stdout(), new TypeReference<>() {
		});
		return raw.stream()
			.map(rr -> new RepoSummary(rr.name(), rr.nameWithOwner(),
					rr.defaultBranchRef() == null ? props.defaultBranchFallback() : rr.defaultBranchRef().name(),
					rr.isArchived(), rr.isFork(), rr.isPrivate()))
			.toList();
	}

	@Override
	public String getDefaultBranch(String slug) {
		CommandResult r = runner.run(null, timeout(), props.ghBinary(), "repo", "view", slug, "--json",
				"defaultBranchRef", "-q", ".defaultBranchRef.name");
		if (!r.ok() || r.stdout().isBlank())
			return props.defaultBranchFallback();
		return r.stdout().trim();
	}

	@Override
	public List<PrSummary> listOpenPrsByPrefix(String slug, String titlePrefix) {
		CommandResult r = runner.run(null, timeout(), props.ghBinary(), "pr", "list", "--repo", slug, "--state", "open",
				"--limit", "200", "--json", "number,title,url,headRefName,baseRefName,createdAt,state");
		if (!r.ok()) {
			log.warn("gh pr list failed for {}: {}", slug, r.shortStderr());
			return List.of();
		}
		List<PrSummary> all = readJson(r.stdout(), new TypeReference<>() {
		});
		return all.stream().filter(p -> p.title() != null && p.title().startsWith(titlePrefix)).toList();
	}

	@Override
	public void closePr(String slug, int number, boolean dryRun) {
		if (dryRun) {
			log.info("[dry-run] gh pr close {}#{}", slug, number);
			return;
		}
		runner.runOrThrow(null, timeout(), props.ghBinary(), "pr", "close", String.valueOf(number), "--repo", slug,
				"--comment", "Superseded by a newer AdvisorBot run.");
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
		runner.run(null, timeout(), props.ghBinary(), "api", "-X", "DELETE",
				"repos/" + slug + "/git/refs/heads/" + branch);
	}

	@Override
	public String createPr(String slug, String base, String head, String title, String body, boolean dryRun) {
		if (dryRun) {
			log.info("[dry-run] gh pr create on {} title={}", slug, title);
			return "dry-run://pr/" + slug;
		}
		CommandResult r = runner.runOrThrow(null, timeout(), props.ghBinary(), "pr", "create", "--repo", slug, "--base",
				base, "--head", head, "--title", title, "--body", body);
		return r.stdout().trim();
	}

	private <T> T readJson(String json, TypeReference<T> ref) {
		try {
			return mapper.readValue(json, ref);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to parse gh JSON: " + e.getMessage(), e);
		}
	}

	private Duration timeout() {
		return props.process().timeoutPerCommand();
	}

	private record RawRepo(String name, String nameWithOwner, DefaultBranchRef defaultBranchRef, boolean isArchived,
			boolean isFork, boolean isPrivate) {
		record DefaultBranchRef(String name) {
		}
	}

}
