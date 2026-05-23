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
 * Application metrics surface.
 *
 * One central place to register and update Micrometer meters. Other classes
 * (service, exception handler, client) hold a reference to this bean and
 * call into it rather than touching MeterRegistry directly — keeps metric
 * names and tag conventions consistent across the codebase.
 *
 * Eager registration: every metric (counters and gauge) is registered in the
 * constructor so that /actuator/metrics/{name} returns a value from boot —
 * not 404 until the first matching request arrives. For tagged counters
 * (conversion.requests.total, conversion.validation.errors) the constructor
 * registers a zero-count placeholder with no tags; real tagged variants are
 * still created on increment. Actuator's aggregate query sums across all
 * variants, so the placeholder (always 0) doesn't affect totals.
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
        // Micrometer caches counters by (name, tags) inside the registry, so
        // this lookup is O(1) after the first call for any given pair. Using
        // the registry.counter(...) shorthand is equivalent to Counter.builder
        // but avoids one Builder allocation per call.
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
