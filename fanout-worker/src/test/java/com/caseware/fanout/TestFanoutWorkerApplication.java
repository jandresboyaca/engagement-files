package com.caseware.fanout;

import org.springframework.boot.SpringApplication;

public class TestFanoutWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.from(FanoutWorkerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
