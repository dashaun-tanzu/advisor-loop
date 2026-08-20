package dev.dashaun.advisorloop.ui;

public enum Status {

	SUCCESS("success"), FAIL("fail"), SKIP("skip");

	private final String label;

	Status(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
