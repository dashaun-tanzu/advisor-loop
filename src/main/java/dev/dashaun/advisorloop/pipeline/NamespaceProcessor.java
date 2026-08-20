package dev.dashaun.advisorloop.pipeline;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.forge.Target;
import dev.dashaun.advisorloop.forge.model.RepoSummary;
import dev.dashaun.advisorloop.ui.Activity;
import dev.dashaun.advisorloop.ui.ActivityReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Iterates every repository in one namespace: a GitHub organization or a GitLab group.
 */
@Component
public class NamespaceProcessor {

	private static final Logger log = LoggerFactory.getLogger(NamespaceProcessor.class);

	private final RepoProcessor repoProcessor;

	private final AdvisorProperties props;

	private final ActivityReporter reporter;

	public NamespaceProcessor(RepoProcessor repoProcessor, AdvisorProperties props, ActivityReporter reporter) {
		this.repoProcessor = repoProcessor;
		this.props = props;
		this.reporter = reporter;
	}

	public void process(ForgeClient forge, Target target, boolean dryRun) {
		process(forge, target, dryRun, Set.of());
	}

	/**
	 * @param repoFilter when non-empty, only repositories whose full slug or bare name
	 * appears here are processed. Everything else is passed over in silence.
	 */
	public void process(ForgeClient forge, Target target, boolean dryRun, Set<String> repoFilter) {
		List<RepoSummary> repos;
		try {
			repos = forge.listRepos(target.namespace());
		}
		catch (Exception e) {
			log.warn("could not list repositories for {}", target, e);
			reporter.fail(target.namespace(), "list repos");
			return;
		}
		for (RepoSummary repo : repos) {
			if (!matches(repoFilter, repo.nameWithOwner()))
				continue;
			if (props.skipArchived() && repo.isArchived())
				continue;
			if (props.skipForks() && repo.isFork())
				continue;
			try {
				repoProcessor.process(forge, repo.nameWithOwner(), repo.defaultBranch(), dryRun);
			}
			catch (Exception e) {
				// One broken repository must never end the pass.
				log.warn("unhandled failure on {}", repo.nameWithOwner(), e);
				reporter.fail(repo.nameWithOwner(), Activity.CLONE);
			}
		}
	}

	/**
	 * Accepts either the full {@code namespace/name} slug or just the repository name.
	 */
	static boolean matches(Set<String> filter, String slug) {
		if (filter == null || filter.isEmpty())
			return true;
		String bare = slug.substring(slug.lastIndexOf('/') + 1);
		for (String wanted : filter) {
			if (wanted.equalsIgnoreCase(slug) || wanted.equalsIgnoreCase(bare))
				return true;
		}
		return false;
	}

}
