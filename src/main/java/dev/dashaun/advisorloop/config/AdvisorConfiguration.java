package dev.dashaun.advisorloop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AdvisorConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
