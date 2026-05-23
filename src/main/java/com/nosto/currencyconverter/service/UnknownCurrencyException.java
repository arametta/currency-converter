package com.nosto.currencyconverter.service;

/**
 * Thrown when a currency code passes regex validation (3 alphabetic chars)
 * but is not a known ISO 4217 code. Handled by GlobalExceptionHandler → 422.
 *
 * 422 (Unprocessable Entity) is the correct status here: the request was
 * syntactically valid (passed @Pattern/@Size) but semantically wrong.
 */
public class UnknownCurrencyException extends RuntimeException {

    public UnknownCurrencyException(String message) {
        super(message);
    }

    public UnknownCurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
