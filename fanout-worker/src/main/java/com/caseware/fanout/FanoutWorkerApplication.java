package com.caseware.fanout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FanoutWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(FanoutWorkerApplication.class, args);
	}

}
