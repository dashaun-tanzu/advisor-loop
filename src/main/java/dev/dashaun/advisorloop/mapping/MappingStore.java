package dev.dashaun.advisorloop.mapping;

import dev.dashaun.advisorloop.advisor.AdvisorClient;
import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.process.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The local, git-free mapping store at {@code <workspace>/mappings}.
 *
 * <p>
 * Mappings are generated one at a time and validated by re-running
 * {@code upgrade-plan get}. Every addition records the previous content of any file it
 * overwrote so a bad mapping can be reverted exactly, rather than by guessing which file
 * to delete.
 */
@Component
public class MappingStore {

	private static final Logger log = LoggerFactory.getLogger(MappingStore.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AdvisorProperties props;

	private final AdvisorClient advisor;

	public MappingStore(AdvisorProperties props, AdvisorClient advisor) {
		this.props = props;
		this.advisor = advisor;
	}

	public Path dir() {
		return props.mappingsDir();
	}

	public void init() {
		try {
			Files.createDirectories(props.mappingsDir());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create mapping store " + props.mappingsDir(), e);
		}
	}

	/**
	 * Runs {@code advisor mapping create -c <coordinate>} in a scratch directory and
	 * installs the generated files into the store.
	 * @return the addition, or empty when advisor failed or produced nothing
	 */
	public Optional<MappingAddition> create(String coordinate) {
		init();
		Path work = props.mappingWorkDir();
		Path generatedDir = work.resolve(".advisor/mappings");
		try {
			deleteRecursively(work);
			Files.createDirectories(work);
		}
		catch (IOException e) {
			log.warn("Could not prepare mapping work dir {}: {}", work, e.getMessage());
			return Optional.empty();
		}

		CommandResult r = advisor.mappingCreate(work, coordinate);
		if (!r.ok()) {
			log.warn("advisor mapping create failed for {}: {}", coordinate, r.shortStderr());
			return Optional.empty();
		}

		List<Path> generated = listJson(generatedDir);
		if (generated.isEmpty()) {
			log.warn("advisor mapping create produced no JSON for {}", coordinate);
			return Optional.empty();
		}

		List<Path> added = new ArrayList<>();
		Map<Path, byte[]> backups = new LinkedHashMap<>();
		try {
			for (Path src : generated) {
				Path target = props.mappingsDir().resolve(src.getFileName().toString());
				byte[] existing = null;
				if (Files.exists(target)) {
					existing = Files.readAllBytes(target);
					backups.put(target, existing);
				}
				Files.write(target, asDelta(existing, Files.readAllBytes(src)));
				added.add(target);
			}
		}
		catch (IOException e) {
			log.warn("Could not install mapping for {}: {}", coordinate, e.getMessage());
			// Undo the partial install so the store is never left half-written.
			rollback(new MappingAddition(coordinate, added, backups));
			return Optional.empty();
		}
		return Optional.of(new MappingAddition(coordinate, added, backups));
	}

	/** Reverts an addition: restores overwritten files, deletes newly created ones. */
	public void rollback(MappingAddition addition) {
		for (Path p : addition.added()) {
			try {
				byte[] previous = addition.backups().get(p);
				if (previous != null) {
					Files.write(p, previous);
				}
				else {
					Files.deleteIfExists(p);
				}
			}
			catch (IOException e) {
				log.warn("Could not roll back {}: {}", p, e.getMessage());
			}
		}
	}

	/**
	 * Reduces a generated mapping to the only part of it that is worth storing: the
	 * coordinates.
	 *
	 * <p>
	 * {@code advisor mapping create -c X} does not return a mapping for X; it returns the
	 * whole project X belongs to, as seen through X. Ask about
	 * {@code spring-boot-grpc-server} and you get the spring-boot project with only the
	 * coordinates that co-occur with grpc-server and only the two versions that artifact
	 * has; ask via {@code spring-boot} and you get twenty-two versions.
	 *
	 * <p>
	 * Three findings decide the shape stored here, all measured against a real 65-file
	 * store:
	 * <ul>
	 * <li>The generated {@code rewrite} graphs <b>never contain a single recipe</b> —
	 * zero across every file. Recipes are what actually rewrite a POM, and advisor ships
	 * them for the projects it knows.</li>
	 * <li>Keeping our {@code rewrite} therefore <b>destroys</b> those built-in recipes,
	 * because {@code override} replaces the block for that slug. The plan still looked
	 * right while {@code upgrade-plan apply} silently produced no diff and no pull
	 * request.</li>
	 * <li>The graphs also contradict each other — of twenty-two version keys shared
	 * across the thirty spring-boot files, <b>none</b> agreed on their requirements — so
	 * there is no honest way to blend them anyway.</li>
	 * </ul>
	 *
	 * <p>
	 * What advisor genuinely lacks is the coordinate list: the Boot 4.x module split it
	 * does not yet enumerate. So the store keeps {@code slug} + the union of coordinates
	 * and an <b>empty</b> {@code rewrite}. The block must be present — omitting it
	 * entirely fails validation with "Failed to load the mapping source" — but empty
	 * leaves the built-in graph and its recipes intact.
	 *
	 * <p>
	 * Renaming colliding slugs was tried and is worse than either: advisor treats the
	 * slug as project identity, so {@code spring-boot-1} became a phantom project and
	 * plans came back naming those instead of spring-boot.
	 * @param existing prior content for this slug, or {@code null} when the file is new
	 */
	byte[] asDelta(byte[] existing, byte[] incoming) throws IOException {
		JsonNode fresh = mapper.readTree(incoming);
		if (!(fresh instanceof ObjectNode source)) {
			return incoming;
		}

		Set<String> coordinates = new TreeSet<>();
		collectCoordinates(source, coordinates);

		ObjectNode result = source.deepCopy();
		if (existing != null && mapper.readTree(existing) instanceof ObjectNode kept) {
			collectCoordinates(kept, coordinates);
			result = kept.deepCopy();
		}

		ArrayNode array = result.putArray("coordinates");
		coordinates.forEach(array::add);
		result.putObject("rewrite");
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
	}

	private static void collectCoordinates(ObjectNode node, Set<String> into) {
		for (JsonNode c : node.path("coordinates")) {
			String value = c.asText(null);
			if (value != null && !value.isBlank()) {
				into.add(value);
			}
		}
	}

	private static List<Path> listJson(Path dir) {
		if (!Files.isDirectory(dir))
			return List.of();
		try (var s = Files.list(dir)) {
			return s.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted()
				.toList();
		}
		catch (IOException e) {
			log.warn("Could not list generated mappings in {}: {}", dir, e.getMessage());
			return List.of();
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir))
			return;
		try (var s = Files.walk(dir)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

}
