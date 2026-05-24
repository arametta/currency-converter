package com.nosto.currencyconverter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

// @EnableCaching activates Spring's cache abstraction (Caffeine, configured in CacheConfig).
@SpringBootApplication
@EnableCaching
public class CurrencyConverterApplication {

    public static void main(String[] args) {
        SpringApplication.run(CurrencyConverterApplication.class, args);
    }
}
