package com.nosto.currencyconverter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nosto.currencyconverter.client.UnknownCurrencyException;
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
 * That's deliberate — these tests cover the service's own logic (incl. cross-
 * rate arithmetic) in isolation. The caching behaviour is covered by the
 * integration test.
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

    // ---- cross-rate math --------------------------------------------------

    @Test
    void eurToNonEur_appliesEurRateDirectly() {
        // EUR is the source — getEurRate("EUR") returns 1, only EUR/USD is fetched.
        when(rateProvider.getEurRate("EUR")).thenReturn(BigDecimal.ONE);
        when(rateProvider.getEurRate("USD")).thenReturn(new BigDecimal("1.08"));

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("100.00"), "EUR", "USD"));

        // 100 * (1.08 / 1) = 108.00
        assertThat(response.convertedAmount()).isEqualByComparingTo("108.00");
        assertThat(response.exchangeRate()).isEqualByComparingTo("1.08");
    }

    @Test
    void nonEurToEur_invertsEurRate() {
        // USD is the source — effectiveRate = 1 / eurToUsd.
        when(rateProvider.getEurRate("USD")).thenReturn(new BigDecimal("1.08"));
        when(rateProvider.getEurRate("EUR")).thenReturn(BigDecimal.ONE);

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("100.00"), "USD", "EUR"));

        // 100 * (1 / 1.08) = 92.5925... → scaled HALF_UP to 2dp = 92.59
        assertThat(response.convertedAmount()).isEqualByComparingTo("92.59");
        assertThat(response.exchangeRate())
                .isEqualByComparingTo(new BigDecimal("0.9259259259"));
    }

    @Test
    void nonEurToNonEur_computesCrossRateViaEur() {
        // The general case: neither currency is EUR. Effective rate is the
        // ratio of the two EUR-based rates.
        when(rateProvider.getEurRate("USD")).thenReturn(new BigDecimal("1.08"));
        when(rateProvider.getEurRate("GBP")).thenReturn(new BigDecimal("0.86"));

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("100.00"), "USD", "GBP"));

        // 100 * (0.86 / 1.08) = 79.6296... → scaled HALF_UP to 2dp = 79.63
        assertThat(response.convertedAmount()).isEqualByComparingTo("79.63");
        // effectiveRate exposed at CROSS_RATE_SCALE (10dp) precision.
        assertThat(response.exchangeRate())
                .isEqualByComparingTo(new BigDecimal("0.7962962963"));
    }

    @Test
    void conversionToZeroDecimalCurrency_roundsCorrectly() {
        // JPY has 0 default fraction digits — proves we're not hardcoding 2.
        when(rateProvider.getEurRate("EUR")).thenReturn(BigDecimal.ONE);
        when(rateProvider.getEurRate("JPY")).thenReturn(new BigDecimal("160.50"));

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("10"), "EUR", "JPY"));

        // 10 * 160.50 = 1605.00 → rounded HALF_UP to 0 dp = 1605
        assertThat(response.convertedAmount()).isEqualByComparingTo("1605");
    }

    // ---- same-currency short-circuit --------------------------------------

    @Test
    void sameSourceAndTarget_returnsOriginalAmountWithoutCallingProvider() {
        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("42.50"), "USD", "USD"));

        assertThat(response.convertedAmount()).isEqualByComparingTo("42.50");
        assertThat(response.exchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(rateProvider, never()).getEurRate(any());
    }

    @Test
    void sameSourceAndTargetAfterNormalisation_shortCircuits() {
        // Mixed case input — same currency after toUpperCase. Must still skip swop.cx.
        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("1"), "usd", "USD"));

        assertThat(response.exchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(rateProvider, never()).getEurRate(any());
    }

    // ---- normalisation ----------------------------------------------------

    @Test
    void currencyCodesAreNormalisedToUppercaseBeforeCallingProvider() {
        when(rateProvider.getEurRate("USD")).thenReturn(new BigDecimal("1.08"));
        when(rateProvider.getEurRate("EUR")).thenReturn(BigDecimal.ONE);

        ConversionResponse response = service.convert(
                new ConversionRequest(new BigDecimal("10"), "usd", "eur"));

        // Provider is called with the uppercased values, never the raw input.
        verify(rateProvider).getEurRate("USD");
        verify(rateProvider).getEurRate("EUR");
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

        verify(rateProvider, never()).getEurRate(any());
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

    @Test
    void amountAboveCap_failsValidation() {
        // Cap is 999,999,999,999.99 — one over is invalid.
        Set<ConstraintViolation<ConversionRequest>> violations = validator.validate(
                new ConversionRequest(new BigDecimal("1000000000000"), "USD", "EUR"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("amount");
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anyMatch(m -> m.contains("realistic"));
    }

    @Test
    void amountAtCap_passesValidation() {
        // Boundary: @DecimalMax is inclusive by default — the cap itself is valid.
        Set<ConstraintViolation<ConversionRequest>> violations = validator.validate(
                new ConversionRequest(new BigDecimal("999999999999.99"), "USD", "EUR"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("amount");
    }
}
