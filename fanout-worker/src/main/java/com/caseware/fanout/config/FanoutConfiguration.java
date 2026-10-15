package com.caseware.fanout.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the fan-out worker. Services and policies are created here only, as beans. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FanoutProperties.class)
public class FanoutConfiguration {
}
