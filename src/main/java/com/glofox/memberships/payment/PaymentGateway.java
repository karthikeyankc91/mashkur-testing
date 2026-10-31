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

	// ----- added for this exercise: the two failure modes the javadoc above
	// already names ("declined or the gateway errors"). The umbrella type is
	// unchanged, so existing callers and stubs keep working. -----------------

	/**
	 * A working gateway refused the charge. This is a definitive answer and is
	 * not retryable as-is, so it surfaces as 402.
	 */
	class PaymentDeclinedException extends PaymentFailedException {
		public PaymentDeclinedException(String message) {
			super(message);
		}
	}

	/**
	 * The gateway could not be reached, or errored before deciding. No answer at
	 * all, so the charge may or may not have landed and a retry is reasonable.
	 * Surfaces as 502.
	 */
	class PaymentGatewayUnavailableException extends PaymentFailedException {
		public PaymentGatewayUnavailableException(String message) {
			super(message);
		}
	}
}
