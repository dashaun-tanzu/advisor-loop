package dev.dashaun.advisorloop.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceProcessorTest {

	@Test
	void an_empty_filter_lets_everything_through() {
		assertThat(NamespaceProcessor.matches(Set.of(), "grp/widget")).isTrue();
		assertThat(NamespaceProcessor.matches(null, "grp/widget")).isTrue();
	}

	@Test
	void it_matches_on_the_full_slug_or_the_bare_name() {
		assertThat(NamespaceProcessor.matches(Set.of("grp/widget"), "grp/widget")).isTrue();
		assertThat(NamespaceProcessor.matches(Set.of("widget"), "grp/widget")).isTrue();
		assertThat(NamespaceProcessor.matches(Set.of("WIDGET"), "grp/widget")).isTrue();
	}

	@Test
	void it_excludes_anything_not_named() {
		assertThat(NamespaceProcessor.matches(Set.of("widget"), "grp/other")).isFalse();
	}

	@Test
	void a_bare_name_does_not_match_a_different_namespace_partially() {
		// "widget" must not match "grp/widget-extra"; the whole name has to line up.
		assertThat(NamespaceProcessor.matches(Set.of("widget"), "grp/widget-extra")).isFalse();
	}

}
