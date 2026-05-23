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
 * Why a Java bean instead of just spring.cache.caffeine.spec=...?
 *   - recordStats() is needed for the cache.hit.rate Micrometer gauge (Step 4).
 *     The properties-style spec string doesn't expose .recordStats().
 *   - maximumSize bounds memory: at ~190 ISO currencies, the worst case is
 *     ~36k unique unordered pairs; 500 is comfortably larger than any
 *     realistic working set but small enough to be unboundedness-proof.
 *   - expireAfterWrite is 5 minutes per spec — short enough that rates never
 *     get more than 5 minutes stale, long enough to absorb a normal burst.
 *
 * @Primary so this CacheManager wins over the one Spring Boot auto-configures
 * from the spring.cache.* properties (we want the stats-enabled one).
 *
 * The cache name "exchangeRates" matches the @Cacheable value in
 * CurrencyConversionService.getExchangeRate; CaffeineCacheManager creates
 * caches lazily on first use unless we set names explicitly here.
 */
@Configuration
public class CacheConfig {

    public static final String EXCHANGE_RATES_CACHE = "exchangeRates";

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(EXCHANGE_RATES_CACHE);
        manager.setCaffeine(caffeineBuilder());
        return manager;
    }

    /**
     * Exposed as a bean too so tests / metrics code can reach it if needed.
     */
    @Bean
    public Caffeine<Object, Object> caffeineBuilder() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(500)
                .recordStats(); // required for the cache.hit.rate gauge
    }
}
