package dev.dashaun.advisorloop.mapping;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One accepted-or-not-yet-accepted mapping generation, carrying everything needed to undo
 * it.
 *
 * @param coordinate the groupId:artifactId that triggered the generation
 * @param added files written into the store
 * @param backups prior content of any file this addition overwrote; absent entries were
 * new
 */
public record MappingAddition(String coordinate, List<Path> added, Map<Path, byte[]> backups) {

	public MappingAddition {
		added = List.copyOf(added);
		backups = Map.copyOf(backups);
	}

	public String describe() {
		return coordinate + " -> " + added.stream().map(p -> p.getFileName().toString()).toList();
	}
}
