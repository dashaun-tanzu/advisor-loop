package dev.dashaun.advisorloop;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Carries the pass result out to {@code main} so the process exit code is meaningful. */
@Component
public class ExitCodeHolder implements ExitCodeGenerator {

	private final AtomicInteger code = new AtomicInteger();

	public void set(int value) {
		code.set(value);
	}

	@Override
	public int getExitCode() {
		return code.get();
	}

}
