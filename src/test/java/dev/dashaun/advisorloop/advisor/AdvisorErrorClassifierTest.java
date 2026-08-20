package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.support.TestProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorErrorClassifierTest {

	private final AdvisorErrorClassifier classifier = new AdvisorErrorClassifier(TestProperties.at(Path.of("/tmp/ws")));

	@Test
	void extracts_coordinate_from_a_missing_mapping_error() {
		ErrorKind kind = classifier.classify("ERROR: no mapping found for com.acme.boot:acme-boot-starter");

		assertThat(kind).isInstanceOf(ErrorKind.MissingMapping.class);
		assertThat(((ErrorKind.MissingMapping) kind).coordinate()).isEqualTo("com.acme.boot:acme-boot-starter");
	}

	@Test
	void recognises_the_missing_mapping_phrasings() {
		assertThat(classifier.classify("Mapping not found for io.acme:widget"))
			.isInstanceOf(ErrorKind.MissingMapping.class);
		assertThat(classifier.classify("Could not find a mapping for io.acme:widget"))
			.isInstanceOf(ErrorKind.MissingMapping.class);
		assertThat(classifier.classify("Unsupported library io.acme:widget detected"))
			.isInstanceOf(ErrorKind.MissingMapping.class);
	}

	@Test
	void a_broken_mapping_file_classifies_as_bad_mapping_not_missing() {
		assertThat(classifier.classify("Duplicate mapping for slug acme-boot"))
			.isInstanceOf(ErrorKind.BadMapping.class);
		assertThat(classifier.classify("Failed to parse custom mapping file acme.json"))
			.isInstanceOf(ErrorKind.BadMapping.class);
		assertThat(classifier.classify("mapping acme.json is malformed")).isInstanceOf(ErrorKind.BadMapping.class);
	}

	@Test
	void unrelated_failures_are_other() {
		assertThat(classifier.classify("Compilation failure: cannot find symbol")).isInstanceOf(ErrorKind.Other.class);
	}

	@Test
	void a_missing_mapping_without_a_parseable_coordinate_is_not_actionable() {
		// No groupId:artifactId to feed to `advisor mapping create`, so there is nothing
		// to do.
		assertThat(classifier.classify("no mapping found for the project")).isInstanceOf(ErrorKind.Other.class);
	}

	@Test
	void blank_input_does_not_blow_up() {
		assertThat(classifier.classify("")).isInstanceOf(ErrorKind.Other.class);
		assertThat(classifier.classify(null)).isInstanceOf(ErrorKind.Other.class);
	}

}
