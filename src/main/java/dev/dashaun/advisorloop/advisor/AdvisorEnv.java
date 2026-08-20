package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the environment that points advisor at the local mapping store.
 *
 * <p>
 * {@code SPRING_ADVISOR_MAPPING_CUSTOM_0_FILEPATH} accepts a folder, in which case
 * advisor reads every {@code .json} inside it. The {@code override} merge strategy makes
 * those files layer on top of the built-in mappings instead of replacing them, which is
 * what keeps a newly generated mapping from colliding with one advisor already ships.
 */
@Component
public class AdvisorEnv {

	private static final Logger log = LoggerFactory.getLogger(AdvisorEnv.class);

	static final String FILEPATH_VAR = "SPRING_ADVISOR_MAPPING_CUSTOM_0_FILEPATH";
	static final String STRATEGY_VAR = "SPRING_ADVISOR_MAPPING_CUSTOM_0_MERGE_STRATEGY";

	private final AdvisorProperties props;

	public AdvisorEnv(AdvisorProperties props) {
		this.props = props;
	}

	public Map<String, String> mappingEnv() {
		Path dir = props.mappingsDir();
		if (!containsMappings(dir)) {
			// Pointing advisor at an empty folder buys nothing and risks a startup
			// complaint.
			return Map.of();
		}
		Map<String, String> env = new LinkedHashMap<>();
		env.put(FILEPATH_VAR, dir.toAbsolutePath().toString());
		env.put(STRATEGY_VAR, props.mappings().mergeStrategy());
		return env;
	}

	private static boolean containsMappings(Path dir) {
		if (!Files.isDirectory(dir))
			return false;
		try (var s = Files.list(dir)) {
			return s.anyMatch(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"));
		}
		catch (IOException e) {
			log.warn("Could not inspect mapping store {}: {}", dir, e.getMessage());
			return false;
		}
	}

}
