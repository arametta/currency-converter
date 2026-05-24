package com.nosto.currencyconverter.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Black-box integration test for POST /api/convert.
 *
 * WireMock stands in for swop.cx so the test is hermetic — no network calls,
 * no real API key, no flakiness from upstream rate updates.
 *
 * All stubs use the EUR-base URL pattern that the new SwopClient calls:
 *   GET /rest/rates/EUR/{currencyCode}
 *
 * The cache is cleared before each test (@BeforeEach) so that one test's
 * cached rate doesn't satisfy another test's request and skew the WireMock
 * call counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "swop.api-key=test-key",
        "management.influx.metrics.export.enabled=false"
})
class CurrencyConversionControllerIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired MockMvc mockMvc;
    @Autowired CacheManager cacheManager;
    @Autowired MeterRegistry meterRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    /**
     * Point swop.base-url at the running WireMock instance. The lambda defers
     * evaluation until Spring needs the value, by which time WireMock has
     * already been started in @BeforeAll.
     */
    @DynamicPropertySource
    static void overrideSwopBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("swop.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        // Evict every cache so one test's cached value doesn't satisfy
        // another's request and skew WireMock call counts.
        for (String name : new String[]{"exchangeRates", "currencies"}) {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    // ---------- happy paths -----------------------------------------------

    @Test
    void eurToUsd_appliesEurBasedRateDirectly() throws Exception {
        // EUR is the source — only EUR/USD must be fetched.
        stubEurRate("USD", "1.08");

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 100.00, "sourceCurrency": "EUR", "targetCurrency": "USD" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCurrency").value("EUR"))
                .andExpect(jsonPath("$.targetCurrency").value("USD"))
                // 100 * 1.08 = 108.00
                .andExpect(jsonPath("$.convertedAmount").value(108.00))
                .andExpect(jsonPath("$.exchangeRate").value(1.08))
                .andExpect(jsonPath("$.formattedAmount", notNullValue()));

        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
    }

    @Test
    void usdToEur_invertsEurBasedRate() throws Exception {
        // USD is the source — only EUR/USD must be fetched, then inverted.
        stubEurRate("USD", "1.08");

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isOk())
                // 100 * (1 / 1.08) = 92.59259... → 92.59
                .andExpect(jsonPath("$.convertedAmount").value(92.59));

        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
    }

    @Test
    void usdToGbp_computesCrossRateViaEur() throws Exception {
        // Neither source nor target is EUR — both EUR-based rates must be fetched.
        stubEurRate("USD", "1.08");
        stubEurRate("GBP", "0.86");

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "GBP" }
                                """))
                .andExpect(status().isOk())
                // 100 * (0.86 / 1.08) = 79.6296... → 79.63
                .andExpect(jsonPath("$.convertedAmount").value(79.63));

        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/GBP")));
    }

    // ---------- validation 400s -------------------------------------------

    @Test
    void negativeAmount_returns400WithErrorMessage() throws Exception {
        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": -5, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]", containsString("amount")));
    }

    @Test
    void invalidCurrencyCodeLength_returns400() throws Exception {
        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 1, "sourceCurrency": "USDX", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ---------- normalisation ---------------------------------------------

    @Test
    void lowercaseCurrencyCodes_returns200() throws Exception {
        // WireMock stub matches the uppercased path — proves normalisation occurred.
        stubEurRate("USD", "1.08");

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 50, "sourceCurrency": "usd", "targetCurrency": "eur" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCurrency").value("USD"))
                .andExpect(jsonPath("$.targetCurrency").value("EUR"));

        wireMock.verify(getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
    }

    // ---------- same-currency short-circuit -------------------------------

    @Test
    void sameSourceAndTarget_returns200WithoutCallingSwop() throws Exception {
        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 42, "sourceCurrency": "USD", "targetCurrency": "USD" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedAmount").value(42.00))
                .andExpect(jsonPath("$.exchangeRate").value(1));

        // Zero calls of any kind — verifies the short-circuit.
        wireMock.verify(exactly(0), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
    }

    // ---------- upstream failure ------------------------------------------

    @Test
    void swopReturns503_apiReturns503() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rest/rates/EUR/USD"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 1, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void swopReturns404_apiReturns422() throws Exception {
        // ISO code that swop.cx doesn't support → 404 upstream → 422 to caller.
        // (We use "XTS" which is the ISO 4217 reserved code for testing —
        // valid in Java's Currency table, so the upfront check doesn't fire.)
        wireMock.stubFor(get(urlPathEqualTo("/rest/rates/EUR/XTS"))
                .willReturn(aResponse().withStatus(404)));

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 1, "sourceCurrency": "EUR", "targetCurrency": "XTS" }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    // ---------- caching ---------------------------------------------------

    @Test
    void duplicateRequest_hitsSwopOnlyOnce() throws Exception {
        stubEurRate("USD", "1.08");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/convert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "amount": 10, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                    """))
                    .andExpect(status().isOk());
        }

        // Three API calls in, one upstream call out. That is the cache working.
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
    }

    @Test
    void cachedRateIsReusedAcrossDifferentPairs() throws Exception {
        // The per-currency cache key (vs the old per-pair key) means an
        // EUR/USD rate fetched for USD→GBP is reusable for USD→EUR and
        // GBP→USD afterwards. This test pins down that behaviour.
        stubEurRate("USD", "1.08");
        stubEurRate("GBP", "0.86");

        // Pair 1: USD → GBP fetches both EUR/USD and EUR/GBP (cache miss × 2).
        postConvert("USD", "GBP");
        // Pair 2: USD → EUR reuses cached EUR/USD; EUR side needs no lookup.
        postConvert("USD", "EUR");
        // Pair 3: GBP → USD reuses both cached entries.
        postConvert("GBP", "USD");

        // Net upstream calls: one EUR/USD, one EUR/GBP — despite three conversions.
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/USD")));
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/EUR/GBP")));
    }

    // ---------- metrics ---------------------------------------------------
    // Pins down that the custom counters in ConversionMetrics actually
    // increment on the live MeterRegistry — not just on a mock.

    @Test
    void conversionRequestsCounter_incrementsAfterSuccessfulConversion() throws Exception {
        stubEurRate("USD", "1.08");

        double before = meterRegistry.find("conversion.requests.total")
                .tags("sourceCurrency", "USD", "targetCurrency", "EUR")
                .counters().stream().mapToDouble(c -> c.count()).sum();

        postConvert("USD", "EUR");

        double after = meterRegistry.find("conversion.requests.total")
                .tags("sourceCurrency", "USD", "targetCurrency", "EUR")
                .counters().stream().mapToDouble(c -> c.count()).sum();

        org.assertj.core.api.Assertions.assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void validationErrorsCounter_incrementsOnBadRequest() throws Exception {
        double before = meterRegistry.find("conversion.validation.errors")
                .tag("field", "amount")
                .counters().stream().mapToDouble(c -> c.count()).sum();

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": -1, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isBadRequest());

        double after = meterRegistry.find("conversion.validation.errors")
                .tag("field", "amount")
                .counters().stream().mapToDouble(c -> c.count()).sum();

        org.assertj.core.api.Assertions.assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void swopErrorsCounter_incrementsOnUpstreamFailure() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rest/rates/EUR/USD"))
                .willReturn(aResponse().withStatus(503)));

        double before = meterRegistry.find("swop.errors.total")
                .counters().stream().mapToDouble(c -> c.count()).sum();

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 1, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isServiceUnavailable());

        double after = meterRegistry.find("swop.errors.total")
                .counters().stream().mapToDouble(c -> c.count()).sum();

        org.assertj.core.api.Assertions.assertThat(after - before).isEqualTo(1.0);
    }

    // ---------- /api/currencies -------------------------------------------

    @Test
    void currenciesEndpoint_returnsActiveCurrenciesSortedByCode() throws Exception {
        stubCurrencies("""
                [
                  { "code": "USD", "name": "United States dollar", "numeric_code": "840", "decimal_digits": 2, "active": true },
                  { "code": "XAF", "name": "CFA franc BEAC",       "numeric_code": "950", "decimal_digits": 0, "active": false },
                  { "code": "EUR", "name": "Euro",                  "numeric_code": "978", "decimal_digits": 2, "active": true },
                  { "code": "GBP", "name": "Pound sterling",        "numeric_code": "826", "decimal_digits": 2, "active": true }
                ]
                """);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("EUR"))
                .andExpect(jsonPath("$[1].code").value("GBP"))
                .andExpect(jsonPath("$[2].code").value("USD"))
                // active and numeric_code must NOT leak into the public DTO
                .andExpect(jsonPath("$[0].active").doesNotExist())
                .andExpect(jsonPath("$[0].numeric_code").doesNotExist())
                .andExpect(jsonPath("$[0].decimal_digits").doesNotExist());
    }

    @Test
    void currenciesEndpoint_returnsServiceUnavailableWhenSwopFails() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rest/currencies"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/currencies"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void currenciesEndpoint_isCachedAcrossRequests() throws Exception {
        // The "currencies" cache (24h TTL) means /api/currencies should only
        // hit swop.cx on the first call; subsequent calls are cache-served.
        stubCurrencies("""
                [
                  { "code": "EUR", "name": "Euro", "numeric_code": "978", "decimal_digits": 2, "active": true }
                ]
                """);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/currencies"))
                    .andExpect(status().isOk());
        }

        // Three API calls in, one upstream call out.
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/currencies")));
    }

    // ---------- helpers ---------------------------------------------------

    private void stubEurRate(String quote, String rate) {
        wireMock.stubFor(get(urlPathEqualTo("/rest/rates/EUR/" + quote))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "base_currency": "EUR",
                                  "quote_currency": "%s",
                                  "quote": %s,
                                  "date": "2026-05-23"
                                }
                                """.formatted(quote, rate))));
    }

    private void stubCurrencies(String jsonBody) {
        wireMock.stubFor(get(urlPathEqualTo("/rest/currencies"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonBody)));
    }

    private void postConvert(String source, String target) throws Exception {
        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 10, "sourceCurrency": "%s", "targetCurrency": "%s" }
                                """.formatted(source, target)))
                .andExpect(status().isOk());
    }
}
