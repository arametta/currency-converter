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
 * Serves the GET /api/currencies endpoint.
 *
 * Three things happen here, in order:
 *   1. Fetch the raw list from swop.cx via SwopClient
 *   2. Drop entries flagged inactive — swop.cx still lists deprecated codes
 *      (e.g. legacy/withdrawn currencies) and we don't want them in the
 *      frontend dropdown
 *   3. Map to CurrencyInfo (narrow public DTO) and sort by code alphabetically
 *
 * Caching: the result is held for 24h in the "currencies" cache configured in
 * CacheConfig. The currency catalogue changes on geopolitical timescales, so
 * 24h is conservative. Caching at the service layer (not the client) means
 * filtering and sorting are also memoised — no per-request CPU spent on a
 * known-stable list.
 *
 * Why @Cacheable here and not on SwopClient.getCurrencies(): the same rule
 * that applies to ExchangeRateProvider — @Cacheable only fires for calls
 * that cross a Spring proxy boundary. The controller calls into this bean
 * (cross-bean → proxy fires); SwopClient calls would also work, but the
 * cleaner place is the layer that owns the post-fetch shaping.
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
