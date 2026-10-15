package com.caseware.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.config.FanoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class FanoutWorkerApplicationIT {

	private static final String[] VALID = {
			"fanout.limits.global=20", "fanout.limits.per-firm=5",
			"fanout.load.timeout=5m", "fanout.load.lease-grace=10s", "fanout.load.connect-timeout=5s",
			"fanout.retry.max-attempts=6", "fanout.retry.base-delay=30s", "fanout.retry.max-delay=30m",
			"fanout.dispatch.period=1s", "fanout.ems.base-url=http://localhost:8089" };

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(FanoutProperties.class)
	static class PropertiesOnly {
	}

	private final ApplicationContextRunner properties = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesOnly.class)
			.withPropertyValues(VALID);

	@Test
	void contextLoads() {
	}

	@Test
	void validPropertiesStart() {
		properties.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void aPerFirmLimitAboveTheGlobalLimitStopsStartup() {
		properties.withPropertyValues("fanout.limits.per-firm=21")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aNegativeLimitStopsStartup() {
		properties.withPropertyValues("fanout.limits.global=-1", "fanout.limits.per-firm=-1")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aMissingLoadTimeoutStopsStartup() {
		new ApplicationContextRunner()
				.withUserConfiguration(PropertiesOnly.class)
				.withPropertyValues(java.util.Arrays.stream(VALID)
						.filter(p -> !p.startsWith("fanout.load.timeout")).toArray(String[]::new))
				.run(context -> assertThat(context).hasFailed());
	}

}
