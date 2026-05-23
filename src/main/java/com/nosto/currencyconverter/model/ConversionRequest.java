package com.nosto.currencyconverter.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Inbound request body for POST /api/convert.
 *
 * Validation is declarative via Jakarta Bean Validation; the controller triggers
 * it with @Valid, and any violations are turned into a 400 response by
 * GlobalExceptionHandler. No manual if-checks in the controller or service.
 *
 * Currency codes are accepted in any case here; the service layer normalises
 * them to uppercase before doing anything with them (silent correction, per spec).
 */
public record ConversionRequest(

        // Monetary amount. BigDecimal — never double/float — to avoid binary
        // floating-point rounding error on currency math.
        @NotNull(message = "amount must not be null")
        @Positive(message = "amount must be positive")
        BigDecimal amount,

        // ISO 4217 alpha-3 code. The @Pattern enforces letters only so we
        // can't be tricked into injecting other characters into the upstream URL.
        @NotNull(message = "sourceCurrency must not be null")
        @NotBlank(message = "sourceCurrency must not be blank")
        @Size(min = 3, max = 3, message = "sourceCurrency must be exactly 3 characters")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "sourceCurrency must be alphabetic")
        String sourceCurrency,

        @NotNull(message = "targetCurrency must not be null")
        @NotBlank(message = "targetCurrency must not be blank")
        @Size(min = 3, max = 3, message = "targetCurrency must be exactly 3 characters")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "targetCurrency must be alphabetic")
        String targetCurrency
) {
}
