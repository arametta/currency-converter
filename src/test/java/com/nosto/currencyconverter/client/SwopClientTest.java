package com.nosto.currencyconverter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nosto.currencyconverter.model.SwopRateResponse;
import java.math.BigDecimal;
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

    @Test
    void successfulResponse_isDeserialisedAndRateReturned() {
        SwopRateResponse body = new SwopRateResponse("USD", "EUR", new BigDecimal("0.92"), "2026-05-23");
        when(restTemplate.exchange(
                contains("/rest/rates/USD/EUR"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenReturn(ResponseEntity.ok(body));

        BigDecimal rate = client.getExchangeRate("USD", "EUR");

        assertThat(rate).isEqualByComparingTo("0.92");
    }

    @Test
    void clientError_throwsExchangeRateUnavailable() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "EUR"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void serverError_throwsExchangeRateUnavailable() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Boom", null, null, null));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "EUR"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void networkTimeout_throwsExchangeRateUnavailable() {
        // RestTemplate wraps SocketTimeoutException as ResourceAccessException.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "EUR"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void emptyResponseBody_throwsExchangeRateUnavailable() {
        // Defensive: never assume the body is non-null.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SwopRateResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.getExchangeRate("USD", "EUR"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }
}
