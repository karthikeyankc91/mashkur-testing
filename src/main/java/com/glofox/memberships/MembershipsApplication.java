package com.glofox.memberships;

import com.glofox.memberships.config.SeedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class MembershipsApplication {

	private static final Logger log = LoggerFactory.getLogger(MembershipsApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(MembershipsApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	void logSeededPlans() {
		log.info("Seeded membership plans (use these ids in your API):");
		SeedData.PLANS.forEach(plan -> log.info(
				"  id={} name=\"{}\" type={} active={} price_cents={} credits={} remaining_slots={}",
				plan.id(),
				plan.name(),
				plan.type(),
				plan.active(),
				plan.priceCents(),
				plan.credits(),
				plan.remainingSlots()));
	}
}
