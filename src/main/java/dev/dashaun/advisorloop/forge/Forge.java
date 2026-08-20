package dev.dashaun.advisorloop.forge;

/** The code hosts advisor-loop knows how to drive. */
public enum Forge {

	GITHUB("organization", "PR"), GITLAB("group", "MR");

	private final String namespaceNoun;

	private final String changeRequestAbbrev;

	Forge(String namespaceNoun, String changeRequestAbbrev) {
		this.namespaceNoun = namespaceNoun;
		this.changeRequestAbbrev = changeRequestAbbrev;
	}

	/** What this forge calls a collection of repositories: organization or group. */
	public String namespaceNoun() {
		return namespaceNoun;
	}

	/** What this forge calls a proposed change: PR on GitHub, MR on GitLab. */
	public String changeRequestAbbrev() {
		return changeRequestAbbrev;
	}

}
