package com.bnaqica.documentreader;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Supplies a {@link MutableClock}. Reset it before each test. */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    MutableClock clock() {
        return new MutableClock();
    }
}
