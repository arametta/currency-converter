package com.nosto.currencyconverter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Mirror of the swop.cx single-pair REST response, e.g.
 *   GET https://swop.cx/rest/rates/{base}/{quote}
 *
 * Documented response shape:
 *   { "base_currency": "EUR", "quote_currency": "USD",
 *     "quote": 1.0912, "date": "2026-05-23" }
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) — the upstream may add fields
 * (e.g. a "source" or attribution field) without breaking us. This is a
 * deliberate forward-compatibility choice; we only consume what we need.
 *
 * Only `quote` is used downstream, but base/quote currency are kept so the
 * deserialised object can be asserted on in tests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwopRateResponse(
        @JsonProperty("base_currency") String baseCurrency,
        @JsonProperty("quote_currency") String quoteCurrency,
        @JsonProperty("quote") BigDecimal quote,
        @JsonProperty("date") String date
) {
}
