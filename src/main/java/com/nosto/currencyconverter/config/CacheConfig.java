package com.nosto.currencyconverter.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Explicit Caffeine configuration.
 *
 * Two caches with very different lifetimes share a single CaffeineCacheManager:
 *
 *   exchangeRates — TTL 5min, max 500 entries
 *       Volatile-ish data. Live FX rates move; 5 minutes is short enough that
 *       a quote never gets meaningfully stale, long enough to absorb bursts.
 *
 *   currencies — TTL 24h, max 1 entry
 *       Reference data. The list of ISO 4217 currencies supported by swop.cx
 *       does not change on minute or hour timescales; 24h is a reasonable
 *       upper bound and keeps the /rest/currencies endpoint of swop.cx out of
 *       our hot path. max 1 because the cache holds exactly one list.
 *
 * Because each cache needs its own Caffeine builder (different TTLs),
 * CaffeineCacheManager.setCaffeine(...) — which applies a single config to
 * every cache the manager creates — isn't enough. We use registerCustomCache
 * to attach a purpose-built Caffeine cache per name.
 *
 * recordStats() is enabled on exchangeRates so the cache.hit.rate gauge in
 * ConversionMetrics has data to read. Currencies cache does NOT need it
 * (it's a single-entry 24h cache; hit rate is trivially near-100% and not
 * worth exposing).
 *
 * @Primary so this CacheManager wins over the one Spring Boot auto-configures
 * from the spring.cache.* properties.
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
