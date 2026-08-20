package dev.dashaun.advisorloop.advisor;

public sealed interface ErrorKind {

	/** Advisor cannot proceed until a mapping exists for this coordinate. */
	record MissingMapping(String coordinate) implements ErrorKind {
	}

	/**
	 * Advisor is unhappy with the mapping files themselves; the last one added is
	 * suspect.
	 */
	record BadMapping(String summary) implements ErrorKind {
	}

	record Other(String summary) implements ErrorKind {
	}

}
