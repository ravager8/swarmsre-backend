package com.swarmsre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentApplication {

	public static void main(String[] args) {
        System.out.println("Agents ready for the kill");
		SpringApplication.run(AgentApplication.class, args);
	}

}
