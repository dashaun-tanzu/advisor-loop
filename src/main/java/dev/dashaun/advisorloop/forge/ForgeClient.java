package dev.dashaun.advisorloop.forge;

import dev.dashaun.advisorloop.forge.model.PrSummary;
import dev.dashaun.advisorloop.forge.model.RepoSummary;

import java.util.List;

/**
 * Everything advisor-loop needs from a code host, so the pipeline never mentions GitHub
 * or GitLab.
 *
 * <p>
 * "PR" is used throughout for what GitLab calls a merge request;
 * {@link Forge#changeRequestAbbrev()} supplies the right word for anything the user sees.
 */
public interface ForgeClient {

	Forge forge();

	/** True when the CLI for this forge is present and authenticated. */
	boolean authStatus();

	/** Every repository in the namespace, whether or not it is in scope for advisor. */
	List<RepoSummary> listRepos(String namespace);

	String getDefaultBranch(String slug);

	/** Open bot pull/merge requests whose title starts with {@code titlePrefix}. */
	List<PrSummary> listOpenPrsByPrefix(String slug, String titlePrefix);

	void closePr(String slug, int number, boolean dryRun);

	void deleteBranch(String slug, String branch, boolean dryRun);

	/**
	 * @return the URL of the created pull/merge request
	 */
	String createPr(String slug, String base, String head, String title, String body, boolean dryRun);

	/** HTTPS URL used to clone {@code slug} from this forge. */
	String cloneUrl(String slug);

	/** Host name used for git credential configuration, e.g. {@code github.com}. */
	String host();

	/** CLI binary providing {@code auth git-credential} for HTTPS pushes. */
	String cliBinary();

}
