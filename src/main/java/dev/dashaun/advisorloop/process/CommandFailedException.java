package dev.dashaun.advisorloop.process;

import java.util.List;

public class CommandFailedException extends RuntimeException {

	private final transient List<String> argv;

	private final transient CommandResult result;

	public CommandFailedException(List<String> argv, CommandResult result) {
		super("Command failed (exit " + result.exitCode() + "): " + String.join(" ", argv) + "\nstderr: "
				+ result.shortStderr());
		this.argv = List.copyOf(argv);
		this.result = result;
	}

	public List<String> argv() {
		return argv;
	}

	public CommandResult result() {
		return result;
	}

}
