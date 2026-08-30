package com.greentech.scheduler_app;

import org.springframework.boot.SpringApplication;

public class TestSchedulerAppApplication {

	public static void main(String[] args) {
		SpringApplication.from(SchedulerAppApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
