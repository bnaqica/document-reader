package com.bnaqica.documentreader;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Supplies a {@link StepClock}, so saved times are known. Reset it before each test. */
@TestConfiguration
public class StepClockConfig {

    @Bean
    StepClock clock() {
        return new StepClock();
    }
}
