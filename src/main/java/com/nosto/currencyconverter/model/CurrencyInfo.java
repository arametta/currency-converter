package com.nosto.currencyconverter.model;

/**
 * Outbound DTO for GET /api/currencies — what the frontend (or any client)
 * sees. Deliberately narrower than SwopCurrencyResponse: we don't expose
 * swop.cx's full payload shape, so changes upstream (new fields, renamed
 * fields, the `active` flag we use internally to filter) don't leak through
 * to our public API.
 */
public record CurrencyInfo(String code, String name) {
}
