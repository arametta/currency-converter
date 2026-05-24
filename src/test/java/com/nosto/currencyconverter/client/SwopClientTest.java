package com.nosto.currencyconverter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nosto.currencyconverter.model.SwopCurrencyResponse;
import com.nosto.currencyconverter.model.SwopRateResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Unit tests for the swop.cx HTTP adapter.
 *
 * RestTemplate is mocked directly (rather than via MockRestServiceServer) so
 * we can simulate every failure mode — including ResourceAccessException for
 * timeouts — with one consistent approach.
 */
@ExtendWith(MockitoExtension.class)
class SwopClientTest {

    @Mock RestTemplate restTemplate;

    private SwopClient client;

    @BeforeEach
    void setUp() {
        client = new SwopClient(restTemplate, "https://swop.cx", "test-key");
    }

    // ---- EUR short-circuit ------------------------------------------------

    @Test
    void getEurRateForEur_returnsOneWithoutHttpCall() {
        BigDecimal rate = client.getEurRate("EUR");

        assertThat(rate).isEqualByComparingTo(BigDecimal.ONE);
        verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class),
                any(HttpEntity.class), eq(SwopRateResponse.class));
    }

    // ---- success ----------------------------------------------------------

    @Test
    void successfulResponse_isDeserialisedAndRateReturned() {
        SwopRateResponse body = new SwopRateResponse(
                "EUR", "USD", new BigDecimal("1.08"), "2026-05-23");
        when(restTemplate.exchange(
                contains("/rest/rates/EUR/USD"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenReturn(ResponseEntity.ok(body));

        BigDecimal rate = client.getEurRate("USD");

        assertThat(rate).isEqualByComparingTo("1.08");
    }

    // ---- failure translation ---------------------------------------------

    @Test
    void notFound_throwsUnknownCurrencyException() {
        // 404 specifically means "swop.cx doesn't support this currency".
        // Caller sees 422, not 503 — the upstream is fine, the input isn't.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatThrownBy(() -> client.getEurRate("XTS"))
                .isInstanceOf(UnknownCurrencyException.class);
    }

    @Test
    void otherClientError_throwsExchangeRateUnavailable() {
        // 401 (bad API key), 429 (rate-limited), etc. all funnel here.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThatThrownBy(() -> client.getEurRate("USD"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void serverError_throwsExchangeRateUnavailable() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Boom", null, null, null));

        assertThatThrownBy(() -> client.getEurRate("USD"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void networkTimeout_throwsExchangeRateUnavailable() {
        // RestTemplate wraps SocketTimeoutException as ResourceAccessException.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.getEurRate("USD"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void emptyResponseBody_throwsExchangeRateUnavailable() {
        // Defensive: never assume the body is non-null.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.getEurRate("USD"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    // ---- getCurrencies ---------------------------------------------------

    @Test
    void getCurrencies_returnsDeserialisedListIncludingInactiveEntries() {
        // The client doesn't filter — that's CurrencyService's job. Verify
        // both active and inactive entries pass through unchanged.
        SwopCurrencyResponse[] body = new SwopCurrencyResponse[]{
                new SwopCurrencyResponse("USD", "United States dollar", true),
                new SwopCurrencyResponse("XAF", "CFA franc BEAC", false),
                new SwopCurrencyResponse("EUR", "Euro", true)
        };
        when(restTemplate.exchange(
                contains("/rest/currencies"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(SwopCurrencyResponse[].class)))
                .thenReturn(ResponseEntity.ok(body));

        List<SwopCurrencyResponse> result = client.getCurrencies();

        assertThat(result).hasSize(3);
        assertThat(result).extracting(SwopCurrencyResponse::code)
                .containsExactly("USD", "XAF", "EUR");
    }

    @Test
    void getCurrencies_serverError_throwsExchangeRateUnavailable() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopCurrencyResponse[].class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Boom", null, null, null));

        assertThatThrownBy(() -> client.getCurrencies())
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void getCurrencies_networkTimeout_throwsExchangeRateUnavailable() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopCurrencyResponse[].class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> client.getCurrencies())
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void getCurrencies_emptyBody_throwsExchangeRateUnavailable() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopCurrencyResponse[].class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.getCurrencies())
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }
}
