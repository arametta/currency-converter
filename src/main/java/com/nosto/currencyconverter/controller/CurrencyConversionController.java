package com.nosto.currencyconverter.controller;

import com.nosto.currencyconverter.model.ConversionRequest;
import com.nosto.currencyconverter.model.ConversionResponse;
import com.nosto.currencyconverter.service.CurrencyConversionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP boundary. No business logic — purely:
 *   1) deserialise + @Valid the request body
 *   2) delegate to the service
 *   3) wrap the result in a ResponseEntity
 *
 * Validation failures don't reach this code: Spring throws
 * MethodArgumentNotValidException before the method body runs, and that's
 * picked up by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api")
public class CurrencyConversionController {

    private final CurrencyConversionService service;

    public CurrencyConversionController(CurrencyConversionService service) {
        this.service = service;
    }

    @PostMapping("/convert")
    public ResponseEntity<ConversionResponse> convert(@Valid @RequestBody ConversionRequest request) {
        return ResponseEntity.ok(service.convert(request));
    }
}
