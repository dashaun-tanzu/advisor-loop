package dev.dashaun.advisorloop.process;

import java.time.Duration;

public record CommandResult(int exitCode, String stdout, String stderr, Duration elapsed) {

	public boolean ok() {
		return exitCode == 0;
	}

	/** stdout and stderr joined, for text matching. */
	public String combined() {
		return (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
	}

	public String shortStderr() {
		if (stderr == null || stderr.isBlank())
			return "";
		String[] lines = stderr.split("\\R");
		StringBuilder sb = new StringBuilder();
		int n = Math.min(lines.length, 20);
		for (int i = 0; i < n; i++)
			sb.append(lines[i]).append('\n');
		if (lines.length > n)
			sb.append("... (").append(lines.length - n).append(" more lines)");
		return sb.toString();
	}
}
