package com.nosto.currencyconverter.model;

import java.math.BigDecimal;

/**
 * Response body for POST /api/convert.
 *
 * Both convertedAmount (raw BigDecimal) and formattedAmount (locale-formatted
 * string) are returned so clients can pick whichever fits their use case
 * without re-implementing locale formatting in the browser.
 */
public record ConversionResponse(
        BigDecimal amount,
        String sourceCurrency,
        String targetCurrency,
        BigDecimal convertedAmount,
        String formattedAmount,
        BigDecimal exchangeRate
) {
}
