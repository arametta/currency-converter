package com.nosto.currencyconverter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.nosto.currencyconverter.config.CacheConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

/**
 * Central place to register and update Micrometer meters. Other classes
 * call into this bean rather than touching MeterRegistry directly so metric
 * names and tag conventions stay consistent.
 *
 * All meters are registered eagerly in the constructor so /actuator/metrics
 * shows them from startup, not just after the first matching request.
 *
 * Note: tagging conversion.requests.total by both currencies creates up to
 * ~36k series. Fine at demo scale; at real scale you'd drop or aggregate tags.
 */
@Component
public class ConversionMetrics {

    static final String METRIC_REQUESTS = "conversion.requests.total";
    static final String METRIC_VALIDATION_ERRORS = "conversion.validation.errors";
    static final String METRIC_SWOP_ERRORS = "swop.errors.total";
    static final String METRIC_SWOP_TIMER = "swop.response.time";
    static final String METRIC_CACHE_HIT_RATE = "cache.hit.rate";

    private final MeterRegistry registry;
    private final CacheManager cacheManager;

    // swop.errors.total has no dynamic tags → safe to cache one Counter
    // reference and reuse it for every increment.
    private final Counter swopErrors;

    public ConversionMetrics(MeterRegistry registry, CacheManager cacheManager) {
        this.registry = registry;
        this.cacheManager = cacheManager;

        // Tagless counter — cached and incremented in place.
        this.swopErrors = Counter.builder(METRIC_SWOP_ERRORS)
                .description("Total swop.cx call failures")
                .register(registry);

        // Zero-count placeholders so /actuator/metrics/{name} is browseable
        // from startup. Real tagged variants are created lazily on increment;
        // the aggregate sum is unaffected because these stay at 0.
        Counter.builder(METRIC_REQUESTS)
                .description("Total conversion requests received")
                .register(registry);
        Counter.builder(METRIC_VALIDATION_ERRORS)
                .description("Validation failures by request field")
                .register(registry);

        // Gauge: read the live Caffeine stats on each scrape. Registered here
        // rather than in @PostConstruct so all metric registration is in one
        // place and ordering with other beans is explicit (the CacheManager is
        // already constructed when this constructor runs).
        Gauge.builder(METRIC_CACHE_HIT_RATE, this, ConversionMetrics::cacheHitRate)
                .description("Caffeine exchangeRates cache hit ratio (0.0–1.0)")
                .register(registry);
    }

    public void recordConversionRequest(String sourceCurrency, String targetCurrency) {
        registry.counter(METRIC_REQUESTS,
                "sourceCurrency", sourceCurrency,
                "targetCurrency", targetCurrency).increment();
    }

    public void recordValidationError(String field) {
        registry.counter(METRIC_VALIDATION_ERRORS, "field", field).increment();
    }

    public void recordSwopError() {
        swopErrors.increment();
    }

    /**
     * Returns a started Timer.Sample. Callers do:
     *     Timer.Sample s = metrics.startSwopTimer();
     *     try { ... } finally { metrics.stopSwopTimer(s); }
     * — this captures both success and failure latencies.
     */
    public Timer.Sample startSwopTimer() {
        return Timer.start(registry);
    }

    public void stopSwopTimer(Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_SWOP_TIMER)
                .description("Duration of swop.cx HTTP calls")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    private double cacheHitRate() {
        var springCache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
        if (!(springCache instanceof CaffeineCache caffeineCache)) {
            return 0.0;
        }
        Cache<?, ?> nativeCache = caffeineCache.getNativeCache();
        CacheStats stats = nativeCache.stats();
        double rate = stats.hitRate();
        // hitRate() returns 1.0 when there have been zero requests; clamp to
        // 0 so dashboards don't show "100% hit rate" on a cold start.
        return stats.requestCount() == 0 ? 0.0 : rate;
    }
}
