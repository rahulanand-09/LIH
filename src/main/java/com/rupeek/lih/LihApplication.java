package com.rupeek.lih;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableMongoRepositories(basePackageClasses = LihApplication.class)
public class LihApplication {
    public static void main(String[] args) {
        SpringApplication.run(LihApplication.class, args);
    }
}
