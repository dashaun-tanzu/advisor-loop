package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the dependencies advisor could not map, by reading {@code upgrade-plan get}
 * output.
 *
 * <p>
 * This is the real signal, and it arrives on the <em>success</em> path: advisor exits 0,
 * writes no error files, and prints a block like
 *
 * <pre>
 * Please request your administrator to configure the projects of the following dependencies:
 *     - io.pivotal.spring.cloud:cloudfoundry-certificate-truster
 *         uses:
 *             - spring-framework
 *         blocking upgrades for:
 *             - spring-boot
 * In order to learn more about publishing upgrade mappings, visit https://...
 * No upgrade plans available - your project seems to be up to date.
 * </pre>
 *
 * <p>
 * Two consequences worth remembering. Advisor never treats a missing mapping as an error,
 * so {@code .advisor/errors/} and the exit code say nothing about it. And the block is
 * followed by "No upgrade plans available", so a repository blocked by a missing mapping
 * is otherwise indistinguishable from one that is genuinely up to date — the coordinates
 * must be extracted before that phrase is believed.
 *
 * <p>
 * Only the bullets carrying a {@code groupId:artifactId} are collected. The nested "uses"
 * and "blocking upgrades for" bullets name bare projects with no colon and are skipped by
 * construction.
 */
@Component
public class UnmappedDependencyScanner {

	private static final Pattern COORDINATE = Pattern.compile("^\\s*-\\s*([A-Za-z0-9._\\-]+:[A-Za-z0-9._\\-]+)\\s*$");

	private final Pattern marker;

	public UnmappedDependencyScanner(AdvisorProperties props) {
		this.marker = Pattern.compile(props.unmappedMarker(), Pattern.CASE_INSENSITIVE);
	}

	/**
	 * @return coordinates advisor asked to have configured, in the order printed, without
	 * duplicates; empty when the output carries no such request
	 */
	public List<String> scan(String output) {
		if (output == null || output.isBlank())
			return List.of();
		String[] lines = output.split("\\R");

		int start = -1;
		for (int i = 0; i < lines.length; i++) {
			if (marker.matcher(lines[i]).find()) {
				start = i + 1;
				break;
			}
		}
		if (start < 0)
			return List.of();

		Set<String> coordinates = new LinkedHashSet<>();
		for (int i = start; i < lines.length; i++) {
			Matcher m = COORDINATE.matcher(lines[i]);
			if (m.matches()) {
				coordinates.add(m.group(1));
			}
		}
		return new ArrayList<>(coordinates);
	}

}
