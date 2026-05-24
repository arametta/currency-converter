package com.nosto.currencyconverter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirror of one entry in the swop.cx /rest/currencies response array.
 *
 * Only the fields we actually consume are declared. numeric_code and
 * decimal_digits are present in the wire payload but intentionally omitted —
 * @JsonIgnoreProperties(ignoreUnknown = true) lets Jackson skip them without
 * failing deserialisation, which also makes us forward-compatible with new
 * fields swop.cx may add later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwopCurrencyResponse(
        @JsonProperty("code") String code,
        @JsonProperty("name") String name,
        @JsonProperty("active") boolean active
) {
}
