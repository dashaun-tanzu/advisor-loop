package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.support.TestProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UnmappedDependencyScannerTest {

	private final UnmappedDependencyScanner scanner = new UnmappedDependencyScanner(
			TestProperties.at(Path.of("/tmp/ws")));

	/**
	 * Real `advisor upgrade-plan get` output from a project with an unmappable
	 * dependency.
	 */
	private static String realUnmappedOutput() throws IOException {
		try (InputStream in = UnmappedDependencyScannerTest.class
			.getResourceAsStream("/upgrade-plan-get-unmapped.txt")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void it_extracts_the_coordinate_advisor_asked_to_have_configured() throws IOException {
		String output = realUnmappedOutput();
		// The trap this exists for: advisor exits 0 and still says there is nothing to
		// upgrade.
		assertThat(output).contains("No upgrade plans available");

		assertThat(scanner.scan(output)).containsExactly("io.pivotal.spring.cloud:cloudfoundry-certificate-truster");
	}

	@Test
	void the_nested_uses_and_blocking_bullets_are_not_coordinates() throws IOException {
		// "- spring-framework", "- spring-boot" etc. are bare project names, not
		// groupId:artifactId.
		assertThat(scanner.scan(realUnmappedOutput()))
			.noneMatch(c -> c.equals("spring-framework") || c.equals("spring-boot"));
	}

	@Test
	void a_healthy_plan_yields_nothing() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/upgrade-plan-get-with-plan.txt")) {
			String plan = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(scanner.scan(plan)).isEmpty();
		}
	}

	@Test
	void several_dependencies_are_returned_in_order_without_duplicates() {
		String output = """
				Please request your administrator to configure the projects of the following dependencies:
					- io.acme:one
						uses:
							- spring-framework
					- io.acme:two
					- io.acme:one
				In order to learn more about publishing upgrade mappings, visit https://example.com
				No upgrade plans available - your project seems to be up to date.
				""";

		assertThat(scanner.scan(output)).containsExactly("io.acme:one", "io.acme:two");
	}

	@Test
	void text_before_the_marker_is_ignored() {
		// The transitive-dependency notice above the marker names projects, not
		// coordinates.
		String output = """
				The projects ["spring-boot"] could not be included in the Upgrade Plan.
					- not.a:coordinate-before-marker
				Please request your administrator to configure the projects of the following dependencies:
					- io.acme:real
				""";

		assertThat(scanner.scan(output)).containsExactly("io.acme:real");
	}

	@Test
	void empty_and_null_are_safe() {
		assertThat(scanner.scan(null)).isEmpty();
		assertThat(scanner.scan("")).isEmpty();
		assertThat(scanner.scan("nothing interesting here")).isEmpty();
	}

}
