package space.harbour.cloud.payments.async;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import space.harbour.cloud.payments.CoffeeType;

import java.math.BigDecimal;

/**
 * One payment inside a {@code POST /api/v1/payments/bulk} request.
 *
 * <p>Unlike the single-payment endpoint (where {@code Store-Id} and
 * {@code Idempotency-Key} travel as headers), here every field is in the JSON body
 * because a single request carries payments for many stores.
 *
 * <p>{@code idempotencyKey} is optional: when present it makes the generated payment
 * id stable, so re-submitting the same bulk request is duplicate-safe end to end.
 */
public record BulkPaymentItem(

		@NotBlank(message = "storeId is required")
		String storeId,

		@NotNull(message = "coffeeType is required")
		CoffeeType coffeeType,

		@NotNull(message = "price is required")
		@DecimalMin(value = "0.0", inclusive = false, message = "price must be greater than zero")
		@Digits(integer = 10, fraction = 2, message = "price may have at most 2 decimal places")
		BigDecimal price,

		@NotNull(message = "currency is required")
		@Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO-4217 code, e.g. EUR")
		String currency,

		@NotNull(message = "loyaltyCardId is required")
		String loyaltyCardId,

		String idempotencyKey) {
}
