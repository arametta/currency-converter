package com.nosto.currencyconverter.controller;

import com.nosto.currencyconverter.client.ExchangeRateUnavailableException;
import com.nosto.currencyconverter.client.UnknownCurrencyException;
import com.nosto.currencyconverter.model.ErrorResponse;
import com.nosto.currencyconverter.service.ConversionMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single place where exceptions are mapped to HTTP status codes + the uniform
 * ErrorResponse envelope. Controllers and services stay free of try/catch
 * noise — they just throw, and this class decides what the client sees.
 *
 * Status mapping:
 *   400 — request body failed Jakarta validation or wasn't valid JSON
 *   422 — currency code was syntactically valid but not a known ISO 4217 code
 *   503 — upstream rate provider unreachable / errored
 *   500 — anything else (catch-all so we never leak a stacktrace)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ConversionMetrics metrics;

    public GlobalExceptionHandler(ConversionMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // Build a flat list of "field: message" strings — easy for any client
        // to render. Returning the structured BindingResult would expose
        // Spring internals and isn't a stable API.
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        // One metric increment per failing field — lets the dashboard show
        // *which* fields fail most often, not just an opaque error count.
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> metrics.recordValidationError(f.getField()));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // Malformed JSON / missing body — still a 400, just from a different
        // code path than Jakarta validation.
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        List.of("Malformed request body")));
    }

    @ExceptionHandler(UnknownCurrencyException.class)
    public ResponseEntity<ErrorResponse> handleUnknownCurrency(UnknownCurrencyException ex) {
        log.warn("Unknown currency: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        List.of(ex.getMessage())));
    }

    @ExceptionHandler(ExchangeRateUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(ExchangeRateUnavailableException ex) {
        // Every translated swop.cx failure (4xx, 5xx, timeout, empty body)
        // funnels through this handler, so the counter is incremented exactly
        // once per upstream failure — no risk of double-counting in the
        // client and the handler.
        metrics.recordSwopError();
        log.error("Exchange rate unavailable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        List.of(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        // Defensive: never leak ex.getMessage() to the client for unhandled
        // exceptions — it might contain internal detail. Log it server-side
        // and return a generic message.
        log.error("Unhandled exception", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        List.of("Internal server error")));
    }
}
