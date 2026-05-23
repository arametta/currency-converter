package com.nosto.currencyconverter.client;

/**
 * Raised when swop.cx is unreachable, times out, or returns an error response.
 * Handled in GlobalExceptionHandler → HTTP 503.
 *
 * Distinct from UnknownCurrencyException (422) which signals a client-side
 * mistake. This one signals an upstream / infrastructure problem.
 *
 * Unchecked because (a) failure modes here are not recoverable in the call
 * stack — they have to bubble to the @RestControllerAdvice, and (b) checked
 * exceptions don't compose well across Spring proxies and @Cacheable.
 */
public class ExchangeRateUnavailableException extends RuntimeException {

    public ExchangeRateUnavailableException(String message) {
        super(message);
    }

    public ExchangeRateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
