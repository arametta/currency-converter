package com.nosto.currencyconverter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vue dev server at localhost:5173.
 * Scoped to /api/** only — actuator stays locked down.
 * Methods limited to what the API actually exposes (GET, POST, OPTIONS).
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
