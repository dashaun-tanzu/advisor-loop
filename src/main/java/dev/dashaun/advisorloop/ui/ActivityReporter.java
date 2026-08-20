package dev.dashaun.advisorloop.ui;

import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * The entire user-facing UI: one line per activity, {@code Org/Repo - activity - status}.
 * Nothing else is printed, so a long crawl stays readable.
 */
@Component
public class ActivityReporter {

	private static final int REPO_W = 52;

	private static final int ACTIVITY_W = 20;

	private final PrintStream out;

	private final Map<Status, Integer> counts = new EnumMap<>(Status.class);

	private boolean headerPrinted;

	public ActivityReporter() {
		this(System.out);
	}

	public ActivityReporter(PrintStream out) {
		this.out = out;
	}

	public synchronized void report(String repo, String activity, Status status) {
		if (!headerPrinted) {
			out.printf("%-" + REPO_W + "s %-" + ACTIVITY_W + "s %s%n", "ORG/REPO", "ACTIVITY", "STATUS");
			out.printf("%-" + REPO_W + "s %-" + ACTIVITY_W + "s %s%n", "-".repeat(REPO_W), "-".repeat(ACTIVITY_W),
					"------");
			headerPrinted = true;
		}
		counts.merge(status, 1, Integer::sum);
		out.printf("%-" + REPO_W + "s %-" + ACTIVITY_W + "s %s%n", clip(repo, REPO_W), clip(activity, ACTIVITY_W),
				status.label());
		out.flush();
	}

	public void success(String repo, String activity) {
		report(repo, activity, Status.SUCCESS);
	}

	public void fail(String repo, String activity) {
		report(repo, activity, Status.FAIL);
	}

	public void skip(String repo, String activity) {
		report(repo, activity, Status.SKIP);
	}

	public synchronized int count(Status s) {
		return counts.getOrDefault(s, 0);
	}

	public synchronized void summary() {
		if (!headerPrinted) {
			out.println("No activity: no repositories with a pom.xml or build.gradle were found.");
			out.flush();
			return;
		}
		out.printf("%n%d success, %d fail, %d skip%n", count(Status.SUCCESS), count(Status.FAIL), count(Status.SKIP));
		out.flush();
	}

	private static String clip(String s, int width) {
		if (s == null)
			return "";
		return s.length() <= width ? s : s.substring(0, width - 1) + "…";
	}

}
