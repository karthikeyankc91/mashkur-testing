package com.glofox.memberships.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only {@link Clock} bean in the system.
 *
 * <p>Confining time to a single injectable bean is what lets every business
 * rule be tested without a wall clock. Step 05 injects it into exactly two
 * collaborators and nowhere else, so no rule can reach for
 * {@code LocalDate.now()} on its own.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        // Server zone, per the assumption recorded in the PRD: a studio operates
        // in one place and timezones are out of scope for this exercise.
        return Clock.systemDefaultZone();
    }
}
