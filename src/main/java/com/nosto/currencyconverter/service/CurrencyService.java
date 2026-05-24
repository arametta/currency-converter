package com.nosto.currencyconverter.service;

import com.nosto.currencyconverter.client.SwopClient;
import com.nosto.currencyconverter.config.CacheConfig;
import com.nosto.currencyconverter.model.CurrencyInfo;
import com.nosto.currencyconverter.model.SwopCurrencyResponse;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Serves GET /api/currencies. Fetches from swop.cx, filters to active
 * entries only, maps to CurrencyInfo, sorts by code, and caches for 24h.
 * Caching at the service layer means filtering and sorting are also memoised.
 */
@Service
public class CurrencyService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyService.class);

    private final SwopClient swopClient;

    public CurrencyService(SwopClient swopClient) {
        this.swopClient = swopClient;
    }

    @Cacheable(CacheConfig.CURRENCIES_CACHE)
    public List<CurrencyInfo> getAvailableCurrencies() {
        log.debug("Cache miss — fetching currency catalogue from swop.cx");
        return swopClient.getCurrencies().stream()
                .filter(SwopCurrencyResponse::active)
                .map(c -> new CurrencyInfo(c.code(), c.name()))
                .sorted(Comparator.comparing(CurrencyInfo::code))
                .toList();
    }
}
