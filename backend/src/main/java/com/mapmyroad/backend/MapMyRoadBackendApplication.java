package com.mapmyroad.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** MapMyRoad backend: a modular monolith (ARCHITECTURE.md section 6, ADR-0005). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MapMyRoadBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapMyRoadBackendApplication.class, args);
    }
}
