package com.nosto.currencyconverter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin policy for the API.
 *
 * The Vue dev server runs at http://localhost:5173 (Vite default); the API
 * runs at http://localhost:8080. Different origins. Without this opt-in,
 * the browser blocks the cross-origin POST after the OPTIONS preflight.
 *
 * Scoped narrowly:
 *   - Path:    /api/** only — actuator and any other endpoints stay locked down
 *   - Origin:  exactly http://localhost:5173 (no wildcard, no credentials)
 *   - Methods: only the verbs we actually expose (GET, POST, OPTIONS)
 *
 * For a production deploy, the allowed origin would come from a property
 * (e.g. app.cors.allowed-origin) so prod, staging, and dev each set their own.
 * Keeping it hardcoded here because the spec doesn't ask for env-config and
 * the demo only ever runs locally.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
