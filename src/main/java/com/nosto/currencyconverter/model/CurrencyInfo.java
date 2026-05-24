package com.nosto.currencyconverter.model;

/**
 * Public DTO for GET /api/currencies. Narrower than SwopCurrencyResponse
 * so internal swop.cx fields don't leak into our API contract.
 */
public record CurrencyInfo(String code, String name) {
}
