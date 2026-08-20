package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns advisor error text into an actionable {@link ErrorKind}. Rules come from
 * {@code advisor.error-rules} so new advisor phrasings can be handled by config alone.
 */
@Component
public class AdvisorErrorClassifier {

	private static final Pattern COORD_OK = Pattern.compile("^[A-Za-z0-9._\\-]+:[A-Za-z0-9._\\-]+$");

	private final List<CompiledRule> rules;

	public AdvisorErrorClassifier(AdvisorProperties props) {
		List<CompiledRule> compiled = new ArrayList<>();
		if (props.errorRules() != null) {
			for (AdvisorProperties.ErrorRule r : props.errorRules()) {
				compiled.add(new CompiledRule(Pattern.compile(r.pattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
						r.kind(), r.coordGroup()));
			}
		}
		this.rules = List.copyOf(compiled);
	}

	public ErrorKind classify(String text) {
		if (text == null || text.isBlank()) {
			return new ErrorKind.Other("(no error content)");
		}
		for (CompiledRule rule : rules) {
			Matcher m = rule.pattern().matcher(text);
			if (!m.find())
				continue;
			if ("MISSING_MAPPING".equalsIgnoreCase(rule.kind())) {
				String coord = extractCoordinate(m, rule.coordGroup());
				if (coord != null)
					return new ErrorKind.MissingMapping(coord);
				// Pattern matched but no usable coordinate: nothing actionable to create.
				return new ErrorKind.Other("missing mapping, coordinate not parseable: " + firstLine(text));
			}
			if ("BAD_MAPPING".equalsIgnoreCase(rule.kind())) {
				return new ErrorKind.BadMapping(firstLine(m.group()));
			}
		}
		return new ErrorKind.Other(firstLine(text));
	}

	private static String extractCoordinate(Matcher m, int group) {
		if (group <= 0 || group > m.groupCount())
			return null;
		String coord = m.group(group);
		return coord != null && COORD_OK.matcher(coord).matches() ? coord : null;
	}

	private static String firstLine(String s) {
		String t = s.strip();
		int nl = t.indexOf('\n');
		String line = nl < 0 ? t : t.substring(0, nl);
		return line.length() > 200 ? line.substring(0, 200) : line;
	}

	private record CompiledRule(Pattern pattern, String kind, int coordGroup) {
	}

}
