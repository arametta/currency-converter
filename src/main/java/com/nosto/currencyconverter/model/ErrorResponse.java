package com.nosto.currencyconverter.model;

import java.util.List;

/**
 * Uniform error envelope returned by GlobalExceptionHandler for every failure
 * mode (400, 422, 500, 503). A single shape makes client-side handling trivial.
 */
public record ErrorResponse(int status, List<String> errors) {
}
