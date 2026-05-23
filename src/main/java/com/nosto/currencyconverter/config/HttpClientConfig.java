package com.nosto.currencyconverter.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate bean for SwopClient.
 *
 * Explicit, conservative timeouts are critical: the default RestTemplate has
 * NO timeouts, which would let a hung upstream stall request threads and
 * eventually exhaust Tomcat's worker pool.
 *
 *   connect = 5s — how long to wait to establish the TCP/TLS handshake.
 *   read    = 10s — how long to wait for the response body once connected.
 *
 * Tuneable via swop.connect-timeout-ms / swop.read-timeout-ms if needed.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
