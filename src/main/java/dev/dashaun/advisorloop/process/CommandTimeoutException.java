package dev.dashaun.advisorloop.process;

import java.time.Duration;

public class CommandTimeoutException extends RuntimeException {

	public CommandTimeoutException(String commandLine, Duration timeout) {
		super("Command timed out after " + timeout + ": " + commandLine);
	}

}
