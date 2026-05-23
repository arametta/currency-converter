package com.nosto.currencyconverter.client;

/**
 * Thrown when a currency code is not usable:
 *   - the upfront check `Currency.getInstance(code)` in
 *     CurrencyConversionService rejects it as not a known ISO 4217 code, OR
 *   - swop.cx responds 404, meaning the code is valid ISO 4217 but swop.cx
 *     does not provide a rate for it.
 *
 * Both cases collapse to HTTP 422 (Unprocessable Entity) in
 * GlobalExceptionHandler: the request was syntactically valid (passed
 * @Pattern/@Size) but semantically wrong.
 *
 * Lives in the client package because SwopClient (the lower layer) needs to
 * throw it; service-layer code is allowed to depend on client-layer types.
 */
public class UnknownCurrencyException extends RuntimeException {

    public UnknownCurrencyException(String message) {
        super(message);
    }

    public UnknownCurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
