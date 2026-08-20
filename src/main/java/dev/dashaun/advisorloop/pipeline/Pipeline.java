package dev.dashaun.advisorloop.pipeline;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import dev.dashaun.advisorloop.forge.Forge;
import dev.dashaun.advisorloop.forge.ForgeClient;
import dev.dashaun.advisorloop.forge.Target;
import dev.dashaun.advisorloop.lock.LockService;
import dev.dashaun.advisorloop.mapping.MappingStore;
import dev.dashaun.advisorloop.ui.ActivityReporter;
import dev.dashaun.advisorloop.ui.Status;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One pass over every configured namespace, on every forge, then stop. */
@Service
public class Pipeline {

	private final NamespaceProcessor namespaceProcessor;

	private final LockService lockService;

	private final MappingStore mappings;

	private final AdvisorProperties props;

	private final ActivityReporter reporter;

	private final Map<Forge, ForgeClient> clients = new EnumMap<>(Forge.class);

	public Pipeline(NamespaceProcessor namespaceProcessor, List<ForgeClient> forgeClients, LockService lockService,
			MappingStore mappings, AdvisorProperties props, ActivityReporter reporter) {
		this.namespaceProcessor = namespaceProcessor;
		this.lockService = lockService;
		this.mappings = mappings;
		this.props = props;
		this.reporter = reporter;
		for (ForgeClient c : forgeClients)
			clients.put(c.forge(), c);
	}

	/**
	 * @return process exit code: 0 clean, 1 completed with failures, 2 could not start.
	 */
	public int run(List<Target> targets, boolean dryRun) {
		return run(targets, dryRun, Set.of());
	}

	/**
	 * @param repoFilter when non-empty, restricts the pass to these repositories. Useful
	 * for inspecting what the bot produces before turning it loose on a namespace.
	 */
	public int run(List<Target> targets, boolean dryRun, Set<String> repoFilter) {
		List<Target> effective = (targets == null || targets.isEmpty()) ? configuredTargets() : targets;
		if (effective.isEmpty()) {
			System.err.println("Nothing to do. Set advisor.orgs or advisor.gitlab.groups, "
					+ "or pass --orgs=foo,bar / --groups=x,y");
			return 2;
		}
		// Only demand authentication for the forges this pass actually touches.
		for (Forge forge : forgesOf(effective)) {
			ForgeClient client = clients.get(forge);
			if (client == null) {
				System.err.println("No client available for " + forge);
				return 2;
			}
			if (!client.authStatus()) {
				System.err.println("`" + client.cliBinary() + "` is unavailable or not authenticated for "
						+ client.host() + ". Install it and run `" + client.cliBinary() + " auth login`.");
				return 2;
			}
		}
		if (!lockService.tryAcquire()) {
			System.err.println("Another advisor-loop run holds the lock: " + lockService.currentHolder());
			return 2;
		}
		try {
			mappings.init();
			for (Target target : effective) {
				namespaceProcessor.process(clients.get(target.forge()), target, dryRun, repoFilter);
			}
		}
		finally {
			lockService.release();
			reporter.summary();
		}
		return reporter.count(Status.FAIL) > 0 ? 1 : 0;
	}

	public List<Target> configuredTargets() {
		List<Target> targets = new ArrayList<>();
		addAll(targets, Forge.GITHUB, props.orgs());
		addAll(targets, Forge.GITLAB, props.gitlab() == null ? null : props.gitlab().groups());
		return targets;
	}

	private static void addAll(List<Target> targets, Forge forge, List<String> namespaces) {
		if (namespaces == null)
			return;
		for (String n : namespaces) {
			if (n != null && !n.isBlank())
				targets.add(new Target(forge, n));
		}
	}

	private static Set<Forge> forgesOf(List<Target> targets) {
		Set<Forge> forges = new LinkedHashSet<>();
		for (Target t : targets)
			forges.add(t.forge());
		return forges;
	}

}
