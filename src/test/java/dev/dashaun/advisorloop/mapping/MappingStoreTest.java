package dev.dashaun.advisorloop.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dashaun.advisorloop.advisor.AdvisorClient;
import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.process.CommandResult;
import dev.dashaun.advisorloop.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MappingStoreTest {

	@TempDir
	Path workspace;

	private AdvisorProperties props;

	private AdvisorClient advisor;

	private MappingStore store;

	@BeforeEach
	void setUp() {
		props = TestProperties.at(workspace);
		advisor = mock(AdvisorClient.class);
		store = new MappingStore(props, advisor);
	}

	/**
	 * Makes the mocked CLI behave like the real one: write JSON into
	 * <workdir>/.advisor/mappings.
	 */
	/**
	 * Makes the mocked CLI behave like the real one: write JSON into
	 * {@code <workdir>/.advisor/mappings}. Uses doAnswer so that re-stubbing between
	 * calls does not invoke the previous answer with null arguments.
	 */
	private void advisorWrites(String fileName, String content) {
		org.mockito.Mockito.doAnswer(inv -> {
			Path work = inv.getArgument(0);
			Path out = work.resolve(".advisor/mappings");
			Files.createDirectories(out);
			Files.writeString(out.resolve(fileName), content);
			return new CommandResult(0, "created", "", Duration.ZERO);
		}).when(advisor).mappingCreate(any(), anyString());
	}

	@Test
	void a_generated_mapping_lands_in_the_store() {
		advisorWrites("widget.json", "{\"slug\":\"widget\"}");

		Optional<MappingAddition> added = store.create("io.acme:widget");

		assertThat(added).isPresent();
		assertThat(props.mappingsDir().resolve("widget.json")).exists();
		assertThat(added.get().backups()).isEmpty();
	}

	@Test
	void rollback_deletes_a_mapping_that_was_newly_created() {
		advisorWrites("widget.json", "{\"slug\":\"widget\"}");
		MappingAddition added = store.create("io.acme:widget").orElseThrow();

		store.rollback(added);

		assertThat(props.mappingsDir().resolve("widget.json")).doesNotExist();
	}

	@Test
	void the_stored_mapping_keeps_coordinates_and_drops_the_rewrite_graph() throws IOException {
		// advisor's generated rewrite graphs carry no recipes at all, and storing them
		// overrides
		// the built-in graph that does, leaving upgrade-plan apply with nothing to run.
		advisorWrites("spring-boot.json", """
				{"slug":"spring-boot","coordinates":["b:spring-boot","b:starter"],
				 "rewrite":{"3.4.x":{"recipes":[]},"3.5.x":{"recipes":[]}}}""");

		store.create("b:starter").orElseThrow();

		JsonNode stored = new ObjectMapper()
			.readTree(Files.readString(props.mappingsDir().resolve("spring-boot.json")));
		assertThat(stored.path("slug").asText()).isEqualTo("spring-boot");
		assertThat(stored.path("coordinates").toString()).contains("b:starter");
		assertThat(stored.path("rewrite").isObject()).as("must be present or validation fails").isTrue();
		assertThat(stored.path("rewrite")).as("but empty, so built-in recipes survive").isEmpty();
	}

	@Test
	void a_second_mapping_for_the_same_slug_unions_coordinates_into_one_file() throws IOException {
		advisorWrites("spring-boot.json", """
				{"slug":"spring-boot","coordinates":["b:spring-boot","b:starter"],"rewrite":{"3.4.x":{}}}""");
		store.create("b:starter").orElseThrow();

		advisorWrites("spring-boot.json", """
				{"slug":"spring-boot","coordinates":["b:spring-boot","b:grpc"],"rewrite":{"4.1.x":{}}}""");
		MappingAddition second = store.create("b:grpc").orElseThrow();

		Path only = props.mappingsDir().resolve("spring-boot.json");
		assertThat(props.mappingsDir().resolve("spring-boot-1.json"))
			.as("renaming the slug fragments the project into a phantom")
			.doesNotExist();
		assertThat(second.added()).containsExactly(only);

		JsonNode merged = new ObjectMapper().readTree(Files.readString(only));
		assertThat(merged.path("slug").asText()).isEqualTo("spring-boot");
		assertThat(merged.path("coordinates").toString()).contains("b:starter")
			.contains("b:grpc")
			.contains("b:spring-boot");
		assertThat(merged.path("rewrite")).isEmpty();
	}

	@Test
	void merging_never_drops_a_coordinate_across_many_generations() throws IOException {
		for (int i = 0; i < 5; i++) {
			advisorWrites("spring-boot.json", "{\"slug\":\"spring-boot\",\"coordinates\":[\"b:shared\",\"b:art" + i
					+ "\"],\"rewrite\":{\"4.1.x\":{}}}");
			store.create("b:art" + i).orElseThrow();
		}

		JsonNode merged = new ObjectMapper()
			.readTree(Files.readString(props.mappingsDir().resolve("spring-boot.json")));
		assertThat(merged.path("coordinates").size()).isEqualTo(6); // shared + art0..art4
		assertThat(Files.list(props.mappingsDir()).count()).isEqualTo(1);
	}

	@Test
	void a_distinct_slug_gets_its_own_file() {
		advisorWrites("spring-ai.json", "{\"slug\":\"spring-ai\",\"coordinates\":[\"x:y\"]}");

		MappingAddition added = store.create("org.springframework.ai:spring-ai-model").orElseThrow();

		assertThat(added.added()).containsExactly(props.mappingsDir().resolve("spring-ai.json"));
	}

	@Test
	void rollback_after_a_merge_restores_the_previous_mapping_exactly() throws IOException {
		advisorWrites("spring-boot.json", "{\"slug\":\"spring-boot\",\"coordinates\":[\"a:one\"]}");
		store.create("a:one").orElseThrow();
		advisorWrites("spring-boot.json", "{\"slug\":\"spring-boot\",\"coordinates\":[\"a:two\"]}");
		MappingAddition second = store.create("a:two").orElseThrow();

		store.rollback(second);

		// The merge overwrote the file, so rollback must restore the exact prior bytes.
		assertThat(Files.readString(props.mappingsDir().resolve("spring-boot.json"))).contains("a:one")
			.doesNotContain("a:two");
	}

	@Test
	void a_failed_mapping_create_installs_nothing() {
		when(advisor.mappingCreate(any(), anyString())).thenReturn(new CommandResult(1, "", "boom", Duration.ZERO));

		assertThat(store.create("io.acme:widget")).isEmpty();
		assertThat(props.mappingsDir()).isEmptyDirectory();
	}

	@Test
	void a_mapping_create_that_produces_no_json_is_treated_as_failure() {
		when(advisor.mappingCreate(any(), anyString()))
			.thenReturn(new CommandResult(0, "nothing to do", "", Duration.ZERO));

		assertThat(store.create("io.acme:widget")).isEmpty();
	}

	@Test
	void the_work_directory_is_cleaned_between_runs() throws IOException {
		// A leftover JSON from an earlier coordinate must not be mistaken for this one's
		// output.
		Path stale = props.mappingWorkDir().resolve(".advisor/mappings");
		Files.createDirectories(stale);
		Files.writeString(stale.resolve("leftover.json"), "{}");
		advisorWrites("widget.json", "{\"slug\":\"widget\"}");

		MappingAddition added = store.create("io.acme:widget").orElseThrow();

		assertThat(added.added()).hasSize(1);
		assertThat(props.mappingsDir().resolve("leftover.json")).doesNotExist();
	}

}
