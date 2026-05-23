package com.nosto.currencyconverter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nosto.currencyconverter.model.ConversionRequest;
import com.nosto.currencyconverter.model.ConversionResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Service-layer unit tests.
 *
 * Note: @Cacheable is NOT exercised here because there is no Spring context.
 * That's deliberate — these tests cover the service's own logic in isolation.
 * The caching behaviour is covered by the integration test (duplicate-call
 * stub assertion).
 */
@ExtendWith(MockitoExtension.class)
class CurrencyConversionServiceTest {

    @Mock ExchangeRateProvider rateProvider;
    @Mock ConversionMetrics metrics;
    @InjectMocks CurrencyConversionService service;

    private static Validator validator;

    @BeforeEach
    void setupValidator() {
        if (validator == null) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                validator = factory.getValidator();
            }
        }
    }

    // ---- conversion math --------------------------------------------------

    @Test
    void validConversion_returnsMathematicallyCorrectResult() {
        when(rateProvider.getExchangeRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("100.00"), "USD", "EUR"));

        // 100.00 * 0.90 = 90.00, scaled to EUR's 2 decimals = 90.00
        assertThat(response.convertedAmount()).isEqualByComparingTo("90.00");
        assertThat(response.exchangeRate()).isEqualByComparingTo("0.90");
        assertThat(response.sourceCurrency()).isEqualTo("USD");
        assertThat(response.targetCurrency()).isEqualTo("EUR");
        assertThat(response.formattedAmount()).isNotBlank();
    }

    @Test
    void conversionToZeroDecimalCurrency_roundsCorrectly() {
        // JPY has 0 default fraction digits — proves we're not hardcoding 2.
        when(rateProvider.getExchangeRate("USD", "JPY")).thenReturn(new BigDecimal("150.55"));

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("10"), "USD", "JPY"));

        // 10 * 150.55 = 1505.50 → rounded HALF_UP to 0 dp = 1506
        assertThat(response.convertedAmount()).isEqualByComparingTo("1506");
    }

    // ---- same-currency short-circuit --------------------------------------

    @Test
    void sameSourceAndTarget_returnsOriginalAmountWithoutCallingSwopClient() {
        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("42.50"), "USD", "USD"));

        assertThat(response.convertedAmount()).isEqualByComparingTo("42.50");
        assertThat(response.exchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(rateProvider, never()).getExchangeRate(any(), any());
    }

    @Test
    void sameSourceAndTargetAfterNormalisation_shortCircuits() {
        // Mixed case input — same currency after toUpperCase. Must still skip swop.cx.
        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("1"), "usd", "USD"));

        assertThat(response.exchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(rateProvider, never()).getExchangeRate(any(), any());
    }

    // ---- normalisation ----------------------------------------------------

    @Test
    void currencyCodesAreNormalisedToUppercaseBeforeCallingSwopClient() {
        when(rateProvider.getExchangeRate("USD", "EUR")).thenReturn(new BigDecimal("0.9"));

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("10"), "usd", "eur"));

        // SwopClient is called with the uppercased values, never the raw input.
        verify(rateProvider).getExchangeRate("USD", "EUR");
        assertThat(response.sourceCurrency()).isEqualTo("USD");
        assertThat(response.targetCurrency()).isEqualTo("EUR");
    }

    // ---- unknown currency -------------------------------------------------

    @Test
    void unknownCurrencyCode_throwsUnknownCurrencyException() {
        // "ZZZ" passes the @Pattern regex but isn't a valid ISO 4217 code.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.convert(
                        new ConversionRequest(new BigDecimal("1"), "ZZZ", "EUR")))
                .isInstanceOf(UnknownCurrencyException.class);

        verify(rateProvider, never()).getExchangeRate(any(), any());
    }

    // ---- Jakarta validation annotations -----------------------------------
    // These verify the @Positive / @NotNull / @Pattern annotations on the DTO
    // are wired correctly. They sit in the service test file because that's
    // where the spec lists them.

    @Test
    void negativeAmount_failsValidation() {
        Set<ConstraintViolation<ConversionRequest>> violations = validator.validate(
                new ConversionRequest(new BigDecimal("-1"), "USD", "EUR"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("amount");
    }

    @Test
    void nullSourceCurrency_failsValidation() {
        Set<ConstraintViolation<ConversionRequest>> violations = validator.validate(
                new ConversionRequest(new BigDecimal("1"), null, "EUR"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("sourceCurrency");
    }

    @Test
    void nullTargetCurrency_failsValidation() {
        Set<ConstraintViolation<ConversionRequest>> violations = validator.validate(
                new ConversionRequest(new BigDecimal("1"), "USD", null));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("targetCurrency");
    }
}
