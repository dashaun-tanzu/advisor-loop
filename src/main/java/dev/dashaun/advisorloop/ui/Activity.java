package dev.dashaun.advisorloop.ui;

import dev.dashaun.advisorloop.forge.Forge;

/** The activity names shown in the middle column of the run output. */
public final class Activity {

	public static final String BUILD_CONFIG_GET = "build-config get";

	public static final String UPGRADE_PLAN_GET = "upgrade-plan get";

	public static final String UPGRADE_PLAN_APPLY = "upgrade-plan apply";

	public static final String PATCH_APPLY = "apply patch";

	public static final String MAPPING_CREATE = "mapping create";

	public static final String MAPPING_ROLLBACK = "mapping rollback";

	/** GitLab calls them merge requests, so the label follows the forge. */
	public static String upgradeRequest(Forge forge) {
		return "upgrade " + forge.changeRequestAbbrev();
	}

	public static String patchRequest(Forge forge) {
		return "patch " + forge.changeRequestAbbrev();
	}

	public static String freshRequest(Forge forge) {
		return "fresh " + forge.changeRequestAbbrev();
	}

	public static String staleRequestDelete(Forge forge) {
		return "stale " + forge.changeRequestAbbrev() + " delete";
	}

	public static final String CLONE = "clone";

	private Activity() {
	}

}
