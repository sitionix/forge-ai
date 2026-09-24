package com.sitionix.forgeagent;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ForgeAgentApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(final String[] args) {
        SpringApplication.run(ForgeAgentApplication.class, args);
    }
}
