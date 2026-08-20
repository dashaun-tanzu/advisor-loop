package dev.dashaun.advisorloop.advisor;

import dev.dashaun.advisorloop.process.CommandResult;

import java.util.List;

public record AdvisorRunResult(AdvisorOutcome outcome, CommandResult command, List<String> errorContents) {

	public boolean ok() {
		return outcome == AdvisorOutcome.OK;
	}

	public boolean errored() {
		return outcome == AdvisorOutcome.ERRORED;
	}

	public boolean noUpgrade() {
		return outcome == AdvisorOutcome.NO_UPGRADE_AVAILABLE;
	}

	/** Error file contents plus the raw command output, for classification. */
	public String diagnosticText() {
		StringBuilder sb = new StringBuilder();
		for (String c : errorContents) {
			if (c != null)
				sb.append(c).append('\n');
		}
		if (command != null)
			sb.append(command.combined());
		return sb.toString();
	}
}
