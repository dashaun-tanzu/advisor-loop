package dev.dashaun.advisorloop.forge;

/** One namespace to crawl: a GitHub organization or a GitLab group. */
public record Target(Forge forge, String namespace) {

	public Target {
		namespace = namespace == null ? "" : namespace.trim();
	}

	@Override
	public String toString() {
		return forge.name().toLowerCase() + ":" + namespace;
	}
}
