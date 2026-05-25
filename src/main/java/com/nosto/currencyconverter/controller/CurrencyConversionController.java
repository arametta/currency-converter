package com.nosto.currencyconverter.controller;

import com.nosto.currencyconverter.model.ConversionRequest;
import com.nosto.currencyconverter.model.ConversionResponse;
import com.nosto.currencyconverter.model.CurrencyInfo;
import com.nosto.currencyconverter.service.CurrencyConversionService;
import com.nosto.currencyconverter.service.CurrencyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP boundary. No business logic — purely:
 *   1) deserialise + @Valid the request body (where applicable)
 *   2) delegate to a service
 *   3) wrap the result in a ResponseEntity
 *
 * Validation failures don't reach this code: Spring throws
 * MethodArgumentNotValidException before the method body runs, and that's
 * picked up by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api")
public class CurrencyConversionController {

    private final CurrencyConversionService conversionService;
    private final CurrencyService currencyService;

    public CurrencyConversionController(
            CurrencyConversionService conversionService,
            CurrencyService currencyService) {
        this.conversionService = conversionService;
        this.currencyService = currencyService;
    }

    /**
     * Converts a monetary amount from one currency to another.
     */
    @PostMapping("/convert")
    public ResponseEntity<ConversionResponse> convert(@Valid @RequestBody ConversionRequest request) {
        return ResponseEntity.ok(conversionService.convert(request));
    }

    /**
     * Returns a list of all available currencies.
     * Cached 24h. API key stays on the server — frontend fetches from here, not swop.cx directly.
     */
    @GetMapping("/currencies")
    public ResponseEntity<List<CurrencyInfo>> currencies() {
        return ResponseEntity.ok(currencyService.getAvailableCurrencies());
    }
}
