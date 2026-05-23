package com.nosto.currencyconverter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.nosto.currencyconverter.config.CacheConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

/**
 * Application metrics surface.
 *
 * One central place to register and update Micrometer meters. Other classes
 * (service, exception handler, client) hold a reference to this bean and
 * call into it rather than touching MeterRegistry directly — keeps metric
 * names and tag conventions consistent across the codebase.
 *
 * The gauge on cache.hit.rate is registered at @PostConstruct time so that
 * the underlying Caffeine cache exists (CaffeineCacheManager creates caches
 * lazily on first call OR eagerly if names are pre-declared, which we do
 * in CacheConfig).
 *
 * Cardinality note: tagging conversion.requests.total with both source and
 * target currency yields up to ~190 * 189 ≈ 36k series. That's tolerable for
 * InfluxDB at this traffic level. If we onboard high-volume customers the
 * tags can be dropped or aggregated.
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

    public ConversionMetrics(MeterRegistry registry, CacheManager cacheManager) {
        this.registry = registry;
        this.cacheManager = cacheManager;
    }

    @PostConstruct
    void registerCacheHitRateGauge() {
        Gauge.builder(METRIC_CACHE_HIT_RATE, this, ConversionMetrics::cacheHitRate)
                .description("Caffeine exchangeRates cache hit ratio (0.0–1.0)")
                .register(registry);
    }

    public void recordConversionRequest(String sourceCurrency, String targetCurrency) {
        Counter.builder(METRIC_REQUESTS)
                .description("Total conversion requests received")
                .tags(Tags.of("sourceCurrency", sourceCurrency, "targetCurrency", targetCurrency))
                .register(registry)
                .increment();
    }

    public void recordValidationError(String field) {
        Counter.builder(METRIC_VALIDATION_ERRORS)
                .description("Validation failures by request field")
                .tags(Tags.of("field", field))
                .register(registry)
                .increment();
    }

    public void recordSwopError() {
        Counter.builder(METRIC_SWOP_ERRORS)
                .description("Total swop.cx call failures")
                .register(registry)
                .increment();
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
