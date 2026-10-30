package com.glofox.memberships.payment;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AlwaysSucceedingPaymentGateway implements PaymentGateway {

	@Override
	public PaymentResult charge(int amountCents, String memberName) {
		return new PaymentResult("txn-" + UUID.randomUUID());
	}
}
