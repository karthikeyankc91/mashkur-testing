package com.glofox.memberships.payment;

/**
 * Port for charging a membership purchase.
 * The default bean always succeeds. In tests, provide a stub that fails when you need to.
 */
public interface PaymentGateway {

	/**
	 * Attempt to charge {@code amountCents} for a purchase.
	 *
	 * @throws PaymentFailedException when the charge is declined or the gateway errors
	 */
	PaymentResult charge(int amountCents, String memberName);

	record PaymentResult(String transactionId) {
	}

	class PaymentFailedException extends RuntimeException {
		public PaymentFailedException(String message) {
			super(message);
		}
	}
}
