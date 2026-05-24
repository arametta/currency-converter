package com.nosto.currencyconverter.model;

import java.util.List;

/**
 * Uniform error envelope for all failure responses (400, 422, 503, 500).
 * One shape makes client-side error handling straightforward.
 */
public record ErrorResponse(int status, List<String> errors) {
}
