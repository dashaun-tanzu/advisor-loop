package dev.dashaun.advisorloop;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.forge.Forge;
import dev.dashaun.advisorloop.forge.Target;
import dev.dashaun.advisorloop.pipeline.Pipeline;
import org.springframework.boot.Banner;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.List;

/**
 * Walks every configured GitHub org once, opening upgrade and patch pull requests, then
 * stops.
 *
 * <pre>
 *   java -jar advisor-loop.jar                       # namespaces from application.yml
 *   java -jar advisor-loop.jar --orgs=foo,bar        # GitHub organizations
 *   java -jar advisor-loop.jar --groups=x,y          # GitLab groups
 *   java -jar advisor-loop.jar --repos=one,two       # restrict to named repositories
 *   java -jar advisor-loop.jar --staleness=1h        # recreate bot PRs older than 1 hour (default 24h)
 *   java -jar advisor-loop.jar --dry-run             # no pushes, no PRs or MRs
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdvisorLoopApplication {

	public static void main(String[] args) {
		applyStalenessAlias(args);
		SpringApplication app = new SpringApplication(AdvisorLoopApplication.class);
		app.setBannerMode(Banner.Mode.OFF);
		app.setLogStartupInfo(false);
		ConfigurableApplicationContext ctx = app.run(args);
		System.exit(SpringApplication.exit(ctx, ctx.getBean(ExitCodeHolder.class)));
	}

	/**
	 * Accepts {@code --staleness=1h} as a friendlier alias for
	 * {@code --advisor.staleness=PT1H}.
	 *
	 * <p>
	 * Both the plain and ISO-8601 forms are understood, so {@code 30m}, {@code 24h} and
	 * {@code PT24H} all work. Setting it as a system property lets the normal
	 * configuration binding apply it, which keeps {@code --advisor.staleness} working
	 * unchanged and leaves the 24 hour default in application.yml as the single source of
	 * truth.
	 */
	static void applyStalenessAlias(String[] args) {
		for (String arg : args) {
			if (!arg.startsWith("--staleness=")) {
				continue;
			}
			String value = arg.substring("--staleness=".length()).trim();
			if (value.isEmpty()) {
				continue;
			}
			try {
				Duration parsed = DurationStyle.detectAndParse(value);
				System.setProperty("advisor.staleness", parsed.toString());
			}
			catch (IllegalArgumentException e) {
				System.err.println("Ignoring unparseable --staleness=" + value
						+ " (expected e.g. 24h, 30m or PT24H); using the configured default.");
			}
		}
	}

	@Bean
	ApplicationRunner singlePass(Pipeline pipeline, AdvisorProperties props, ExitCodeHolder exitCode) {
		return args -> {
			// Either flag alone overrides configuration; together they crawl both forges.
			List<Target> targets = new ArrayList<>();
			targets.addAll(parse(args, "orgs", Forge.GITHUB));
			targets.addAll(parse(args, "groups", Forge.GITLAB));
			boolean dryRun = args.containsOption("dry-run") || props.dryRun();
			Set<String> repoFilter = args.containsOption("repos")
					? Arrays.stream(args.getOptionValues("repos").get(0).split(","))
						.map(String::trim)
						.filter(v -> !v.isEmpty())
						.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
					: Set.of();
			try {
				exitCode.set(pipeline.run(targets, dryRun, repoFilter));
			}
			catch (Exception e) {
				// There is no console appender, so an escaping exception would exit
				// silently.
				System.err.println("advisor-loop failed to complete: " + e);
				exitCode.set(2);
			}
		};
	}

	private static List<Target> parse(ApplicationArguments args, String option, Forge forge) {
		if (!args.containsOption(option) || args.getOptionValues(option).isEmpty()) {
			return List.of();
		}
		return Arrays.stream(args.getOptionValues(option).get(0).split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(n -> new Target(forge, n))
			.toList();
	}

}
