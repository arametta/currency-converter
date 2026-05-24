package com.nosto.currencyconverter.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two Caffeine caches with different TTLs:
 *   exchangeRates — 5 min, max 500 entries. FX rates move; 5 min is
 *                   short enough to stay fresh, long enough to absorb bursts.
 *   currencies    — 24h, max 1 entry. The ISO currency list is stable;
 *                   no point hitting swop.cx more than once a day for it.
 *
 * registerCustomCache is used (not setCaffeine) because each cache needs
 * its own TTL — setCaffeine applies one config to everything.
 * recordStats() only on exchangeRates — the cache.hit.rate gauge reads from it.
 */
@Configuration
public class CacheConfig {

    public static final String EXCHANGE_RATES_CACHE = "exchangeRates";
    public static final String CURRENCIES_CACHE = "currencies";

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        manager.registerCustomCache(EXCHANGE_RATES_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .maximumSize(500)
                        .recordStats() // required for the cache.hit.rate gauge
                        .build());

        manager.registerCustomCache(CURRENCIES_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofHours(24))
                        .maximumSize(1)
                        .build());

        return manager;
    }
}
