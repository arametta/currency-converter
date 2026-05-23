package com.nosto.currencyconverter.service;

import com.nosto.currencyconverter.client.SwopClient;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Caching boundary in front of SwopClient.
 *
 * Why is this a separate bean instead of a method on CurrencyConversionService?
 * Spring's @Cacheable is implemented via AOP proxies. Self-invocation —
 * e.g. CurrencyConversionService.convert() calling this.getExchangeRate() —
 * goes through the actual object reference, not the proxy, so the cache
 * advice never fires. Putting the @Cacheable method on a separate Spring bean
 * means CurrencyConversionService always reaches it through the proxy, and
 * the cache works as intended.
 *
 * This is a small deviation from the literal spec ("annotate the method in
 * CurrencyConversionService that calls SwopClient") needed to make the cache
 * actually function. The behaviour the spec asks for — cache the rate by
 * currency pair with 5-minute TTL, do not cache the full conversion — is
 * preserved exactly.
 */
@Component
public class ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateProvider.class);

    private final SwopClient swopClient;
    private final ConversionMetrics metrics;

    public ExchangeRateProvider(SwopClient swopClient, ConversionMetrics metrics) {
        this.swopClient = swopClient;
        this.metrics = metrics;
    }

    /**
     * @Cacheable intercepts before the method body, so this body — and the
     * timer it runs — only executes on cache misses. That's the right scope
     * for "swop.cx response time": we don't want sub-millisecond cache hits
     * polluting the latency distribution.
     */
    @Cacheable(value = "exchangeRates", key = "#baseCurrency + '_' + #quoteCurrency")
    public BigDecimal getExchangeRate(String baseCurrency, String quoteCurrency) {
        log.debug("Cache miss — fetching {}/{} from swop.cx", baseCurrency, quoteCurrency);
        Timer.Sample sample = metrics.startSwopTimer();
        try {
            return swopClient.getExchangeRate(baseCurrency, quoteCurrency);
        } finally {
            // Recorded for both success and failure paths — failure latency
            // is just as useful as success latency for monitoring.
            metrics.stopSwopTimer(sample);
        }
    }
}
