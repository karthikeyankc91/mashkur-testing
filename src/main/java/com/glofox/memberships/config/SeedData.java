package com.glofox.memberships.config;

import java.util.List;
import java.util.UUID;

/**
 * Pre-seeded membership plans for the exercise.
 * Load these into your store at startup — candidates do not need a "create plan" endpoint.
 */
public final class SeedData {

	private SeedData() {
	}

	public static final UUID MONTHLY_UNLIMITED_ID =
			UUID.fromString("11111111-1111-1111-1111-111111111111");
	public static final UUID TEN_CLASS_PACK_ID =
			UUID.fromString("22222222-2222-2222-2222-222222222222");
	public static final UUID LAUNCH_PROMO_PACK_ID =
			UUID.fromString("33333333-3333-3333-3333-333333333333");
	public static final UUID LEGACY_PLAN_ID =
			UUID.fromString("44444444-4444-4444-4444-444444444444");

	public static final List<PlanSeed> PLANS = List.of(
			new PlanSeed(
					MONTHLY_UNLIMITED_ID,
					"Monthly Unlimited",
					"unlimited",
					true,
					8_000,
					null,
					null),
			new PlanSeed(
					TEN_CLASS_PACK_ID,
					"10-Class Pack",
					"pack",
					true,
					5_000,
					10,
					null),
			new PlanSeed(
					LAUNCH_PROMO_PACK_ID,
					"Launch Promo Pack",
					"pack",
					true,
					2_500,
					5,
					5),
			new PlanSeed(
					LEGACY_PLAN_ID,
					"Legacy Plan",
					"unlimited",
					false,
					6_000,
					null,
					null));

	/**
	 * @param remainingSlots only the Launch Promo Pack has limited inventory; null means unlimited inventory
	 * @param credits class credits for pack plans; null for unlimited plans
	 */
	public record PlanSeed(
			UUID id,
			String name,
			String type,
			boolean active,
			int priceCents,
			Integer credits,
			Integer remainingSlots) {
	}
}
