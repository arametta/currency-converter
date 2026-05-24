package com.nosto.currencyconverter.model;

import java.math.BigDecimal;

/**
 * Response for POST /api/convert. Returns both raw convertedAmount and
 * formatted formattedAmount so clients don't need to re-implement
 * locale formatting on their side.
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
