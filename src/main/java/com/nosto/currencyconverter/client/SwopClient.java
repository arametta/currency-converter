package com.nosto.currencyconverter.client;

import com.nosto.currencyconverter.model.SwopRateResponse;
import java.math.BigDecimal;
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
 * Thin HTTP adapter around the swop.cx single-pair rate endpoint.
 *
 * Contract:
 *   GET {base-url}/rest/rates/{base}/{quote}
 *   Header: Authorization: ApiKey <key>
 *
 * The class is intentionally narrow — one public method, no business logic.
 * Caching and currency normalisation live in the service layer; this class
 * only knows how to talk HTTP to swop.cx and translate failures.
 */
@Component
public class SwopClient {

    private static final Logger log = LoggerFactory.getLogger(SwopClient.class);

    // All injected dependencies are final + constructor-injected (per spec).
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
     * Fetches the live exchange rate for converting 1 unit of baseCurrency into
     * quoteCurrency.
     *
     * Translates every failure mode into ExchangeRateUnavailableException so
     * the upper layers don't have to know about HTTP at all:
     *   - 4xx from swop.cx → ExchangeRateUnavailableException (auth, bad code, etc.)
     *   - 5xx from swop.cx → ExchangeRateUnavailableException
     *   - network/timeout  → ExchangeRateUnavailableException
     *   - missing body / malformed → ExchangeRateUnavailableException
     */
    public BigDecimal getExchangeRate(String baseCurrency, String quoteCurrency) {
        // UriComponentsBuilder applies URL encoding to path segments. Combined
        // with the @Pattern alphabetic check in ConversionRequest, this defends
        // against any injection into the upstream URL.
        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("rest", "rates", baseCurrency, quoteCurrency)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        // swop.cx auth scheme: literal "ApiKey <token>", not "Bearer".
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
                log.error("swop.cx returned empty body for {}/{}", baseCurrency, quoteCurrency);
                throw new ExchangeRateUnavailableException(
                        "Exchange rate provider returned an empty response");
            }
            return body.quote();

        } catch (HttpClientErrorException e) {
            // 4xx — likely bad API key, unknown currency, or rate-limited.
            // We surface this as 503 from our service because, from the caller's
            // perspective, the upstream rate provider is "not currently usable".
            // (A bad currency is already prevented by Currency.getInstance() in
            // the service layer, so this branch most often means auth/quota.)
            log.error("swop.cx returned client error {} for {}/{}",
                    e.getStatusCode(), baseCurrency, quoteCurrency);
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider rejected the request: " + e.getStatusCode(), e);

        } catch (HttpServerErrorException e) {
            log.error("swop.cx returned server error {} for {}/{}",
                    e.getStatusCode(), baseCurrency, quoteCurrency);
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider is currently unavailable", e);

        } catch (ResourceAccessException e) {
            // Connect failure, read timeout, DNS, etc.
            log.error("swop.cx unreachable for {}/{}: {}",
                    baseCurrency, quoteCurrency, e.getMessage());
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider is unreachable", e);
        }
    }
}
