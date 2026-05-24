package com.nosto.currencyconverter.client;

import com.nosto.currencyconverter.model.SwopCurrencyResponse;
import com.nosto.currencyconverter.model.SwopRateResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP adapter for swop.cx. Always fetches EUR-based rates —
 * the free tier doesn't support other base currencies.
 * Translates HTTP failures into domain exceptions so callers
 * don't need to know about RestTemplate.
 */
@Component
public class SwopClient {

    private static final Logger log = LoggerFactory.getLogger(SwopClient.class);
    private static final String EUR = "EUR";

    // All injected dependencies are final + constructor-injected.
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public SwopClient(
            RestTemplate restTemplate,
            @Value("${swop.base-url}") String baseUrl,
            @Value("${swop.api-key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Fetches the EUR → currencyCode exchange rate from swop.cx.
     *
     * Returns BigDecimal.ONE for "EUR" without making any HTTP call —
     * the EUR/EUR rate is always 1 by definition and skipping the call also
     * saves an API-quota credit.
     */
    public BigDecimal getEurRate(String currencyCode) {
        if (EUR.equals(currencyCode)) {
            return BigDecimal.ONE;
        }

        // URL-encodes path segments and prevents injection into the upstream URL.
        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("rest", "rates", EUR, currencyCode)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        // swop.cx auth scheme is literal "ApiKey <token>", not "Bearer".
        headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKey);
        headers.set(HttpHeaders.ACCEPT, "application/json");

        try {
            ResponseEntity<SwopRateResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    SwopRateResponse.class);

            SwopRateResponse body = response.getBody();
            if (body == null || body.quote() == null) {
                log.error("swop.cx returned empty body for EUR/{}", currencyCode);
                throw new ExchangeRateUnavailableException(
                        "Exchange rate provider returned an empty response");
            }
            return body.quote();

        } catch (HttpClientErrorException.NotFound e) {
            // 404 specifically signals "currency code unknown to swop.cx".
            // The ISO code may be valid in Java's Currency table but not in
            // swop.cx's supported set — treat that as 422 to the caller, not
            // 503: the upstream is fine, the input is the problem.
            log.warn("swop.cx has no rate for EUR/{}: {}", currencyCode, e.getStatusCode());
            throw new UnknownCurrencyException(
                    "Currency not supported by exchange rate provider: " + currencyCode, e);

        } catch (HttpClientErrorException e) {
            // Other 4xx — bad API key, rate-limited, malformed request. From
            // the caller's perspective the upstream is "not currently usable".
            log.error("swop.cx returned client error {} for EUR/{}",
                    e.getStatusCode(), currencyCode);
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider rejected the request: " + e.getStatusCode(), e);

        } catch (HttpServerErrorException e) {
            log.error("swop.cx returned server error {} for EUR/{}",
                    e.getStatusCode(), currencyCode);
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider is currently unavailable", e);

        } catch (ResourceAccessException e) {
            // Connect failure, read timeout, DNS, etc.
            log.error("swop.cx unreachable for EUR/{}: {}", currencyCode, e.getMessage());
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider is unreachable", e);
        }
    }

    public List<SwopCurrencyResponse> getCurrencies() {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("rest", "currencies")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKey);
        headers.set(HttpHeaders.ACCEPT, "application/json");

        try {
            // Array deserialisation is simpler than ParameterizedTypeReference
            // for a JSON top-level array and avoids the anonymous-subclass
            // boilerplate. Arrays.asList wraps without copying.
            ResponseEntity<SwopCurrencyResponse[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    SwopCurrencyResponse[].class);

            SwopCurrencyResponse[] body = response.getBody();
            if (body == null) {
                log.error("swop.cx returned empty body for /rest/currencies");
                throw new ExchangeRateUnavailableException(
                        "Exchange rate provider returned an empty currency list");
            }
            return Arrays.asList(body);

        } catch (HttpClientErrorException e) {
            log.error("swop.cx returned client error {} for /rest/currencies",
                    e.getStatusCode());
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider rejected the request: " + e.getStatusCode(), e);

        } catch (HttpServerErrorException e) {
            log.error("swop.cx returned server error {} for /rest/currencies",
                    e.getStatusCode());
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider is currently unavailable", e);

        } catch (ResourceAccessException e) {
            log.error("swop.cx unreachable for /rest/currencies: {}", e.getMessage());
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider is unreachable", e);
        }
    }
}
