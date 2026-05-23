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
        // Evict any rate cached by a previous test so each test sees a clean slate.
        var cache = cacheManager.getCache("exchangeRates");
        if (cache != null) {
            cache.clear();
        }
    }

    // ---------- happy path ------------------------------------------------

    @Test
    void validInput_returns200AndCorrectResponseStructure() throws Exception {
        stubRate("USD", "EUR", "0.92");

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.sourceCurrency").value("USD"))
                .andExpect(jsonPath("$.targetCurrency").value("EUR"))
                .andExpect(jsonPath("$.convertedAmount").value(92.00))
                .andExpect(jsonPath("$.exchangeRate").value(0.92))
                .andExpect(jsonPath("$.formattedAmount", notNullValue()));
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
        stubRate("USD", "EUR", "0.92");

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 50, "sourceCurrency": "usd", "targetCurrency": "eur" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCurrency").value("USD"))
                .andExpect(jsonPath("$.targetCurrency").value("EUR"));

        wireMock.verify(getRequestedFor(urlPathEqualTo("/rest/rates/USD/EUR")));
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

        // Assert zero calls to swop.cx — verifies the short-circuit.
        wireMock.verify(exactly(0), getRequestedFor(urlPathEqualTo("/rest/rates/USD/USD")));
    }

    // ---------- upstream failure ------------------------------------------

    @Test
    void swopReturns503_apiReturns503() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rest/rates/USD/EUR"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/api/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 1, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    // ---------- caching ---------------------------------------------------

    @Test
    void duplicateRequest_hitsSwopOnlyOnce() throws Exception {
        stubRate("USD", "EUR", "0.92");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/convert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "amount": 10, "sourceCurrency": "USD", "targetCurrency": "EUR" }
                                    """))
                    .andExpect(status().isOk());
        }

        // Three API calls in, one upstream call out. That is the cache working.
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/rest/rates/USD/EUR")));
    }

    // ---------- helpers ---------------------------------------------------

    private void stubRate(String base, String quote, String rate) {
        wireMock.stubFor(get(urlPathEqualTo("/rest/rates/" + base + "/" + quote))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "base_currency": "%s",
                                  "quote_currency": "%s",
                                  "quote": %s,
                                  "date": "2026-05-23"
                                }
                                """.formatted(base, quote, rate))));
    }
}
