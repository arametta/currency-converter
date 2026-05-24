# Currency Converter

A REST API in Java + Spring Boot that converts a monetary amount between
currencies using live exchange rates from [swop.cx](https://swop.cx/).

Built as a technical assignment for a Nosto Software Team Lead interview.

## Stack

| Concern     | Choice                                |
|-------------|---------------------------------------|
| Language    | Java 21                               |
| Framework   | Spring Boot 3.4.1                     |
| Build       | Maven (wrapper bundled — `./mvnw`)    |
| HTTP client | `RestTemplate`                        |
| Cache       | Caffeine (rates 5min, currencies 24h) |
| Validation  | Jakarta Bean Validation               |
| Tests       | JUnit 5 + Mockito + WireMock          |
| Metrics     | Micrometer → InfluxDB 1.8 → Grafana   |

## Endpoints

### `POST /api/convert`

Request:
```json
{ "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "EUR" }
```

Response (200):
```json
{
  "amount": 100.00,
  "sourceCurrency": "USD",
  "targetCurrency": "EUR",
  "convertedAmount": 92.00,
  "formattedAmount": "€92.00",
  "exchangeRate": 0.92
}
```

Error envelope (400 / 422 / 503 / 500):
```json
{ "status": 400, "errors": ["amount: amount must be positive"] }
```

### `GET /api/currencies`

Returns the list of currencies supported by swop.cx, cached for 24 hours.
The frontend uses this to populate dropdowns — the swop.cx API key never
leaves the backend.

Example response (200):
```json
[
  { "code": "EUR", "name": "Euro" },
  { "code": "GBP", "name": "Pound sterling" },
  { "code": "USD", "name": "United States dollar" }
]
```

Only active currencies, sorted alphabetically by code.

### Status codes

| Code | Meaning                                                    |
|------|------------------------------------------------------------|
| 200  | Successful conversion                                      |
| 400  | Validation failure (negative amount, bad currency length…) |
| 422  | Currency code is syntactically valid but unknown to swop.cx |
| 503  | swop.cx is unreachable or returned an error                |
| 500  | Unhandled server error                                     |

## Requirements

- Java 21
- Maven (or use the bundled `./mvnw`)
- Docker (optional, needed for monitoring stack)
- A swop.cx API key set as `SWOP_API_KEY` env var

## Building and running

### Local

```bash
export SWOP_API_KEY=your_key_here
./mvnw clean package -DskipTests
java -jar target/currency-converter-0.0.1-SNAPSHOT.jar
```

The API listens on `http://localhost:8080`.

### Docker

```bash
./mvnw clean package -DskipTests
docker build -t currency-converter .
docker run -p 8080:8080 -e SWOP_API_KEY=your_key currency-converter
```

### Full stack (app + InfluxDB + Grafana)

```bash
export SWOP_API_KEY=your_key
./mvnw clean package -DskipTests
docker compose up --build
```

| Service      | URL                                 |
|--------------|-------------------------------------|
| API          | http://localhost:8080               |
| Grafana      | http://localhost:3000 (admin/admin) |
| InfluxDB API | http://localhost:8086               |

The Grafana datasource and dashboard are provisioned automatically.

### Frontend (Vue.js 3)

```bash
cd frontend
npm install
npm run dev
```

The dev server runs on `http://localhost:5173`. The backend must be running
first — the frontend calls it directly and CORS is configured for `:5173`.

## Usage with curl

```bash
# EUR to USD
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100.00, "sourceCurrency": "EUR", "targetCurrency": "USD" }'

# USD to EUR
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "EUR" }'

# USD to GBP (cross-rate via EUR)
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "GBP" }'

# Same currency — returns immediately without calling swop.cx
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 42.50, "sourceCurrency": "USD", "targetCurrency": "USD" }'

# Lowercase codes — normalised to uppercase automatically
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 50, "sourceCurrency": "usd", "targetCurrency": "eur" }'

# JPY — zero-decimal currency
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100, "sourceCurrency": "EUR", "targetCurrency": "JPY" }'

# Validation failure → 400
curl -i -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": -5, "sourceCurrency": "USD", "targetCurrency": "EUR" }'

# Unknown ISO code → 422
curl -i -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 1, "sourceCurrency": "ZZZ", "targetCurrency": "EUR" }'

# List currencies
curl -sS http://localhost:8080/api/currencies | jq
```

### Actuator

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8080/actuator/metrics/conversion.requests.total | jq
curl -sS http://localhost:8080/actuator/metrics/swop.response.time | jq
curl -sS http://localhost:8080/actuator/metrics/cache.hit.rate | jq
```

### InfluxDB (useful if Grafana panels look empty)

```bash
# Check InfluxDB is up
curl -sS http://localhost:8086/ping -i | head -3

# See all measurements
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SHOW MEASUREMENTS' | jq

# Latest conversion counts per currency pair
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SELECT last("value") FROM "conversion_requests_total" GROUP BY "sourceCurrency","targetCurrency"' | jq
```

Note: metric names with dots in code (`conversion.requests.total`) become
underscores in InfluxDB (`conversion_requests_total`).

## Tests

```bash
./mvnw test
```

42 tests across:

- `CurrencyConversionServiceTest` — conversion logic and validation (11 tests)
- `CurrencyServiceTest` — currency list mapping, sorting, filtering (3 tests)
- `SwopClientTest` — HTTP failure handling for both swop.cx endpoints (11 tests)
- `CurrencyConversionControllerIntegrationTest` — full Spring Boot context
  with WireMock standing in for swop.cx (17 tests)

Tests are hermetic — no network calls, no real API key needed.

## Configuration

| Property                                   | Default                 | Notes                    |
|--------------------------------------------|-------------------------|--------------------------|
| `swop.api-key`                             | `${SWOP_API_KEY}`       | Required at runtime      |
| `swop.base-url`                            | `https://swop.cx`       |                          |
| `spring.cache.type`                        | `caffeine`              | TTL set in `CacheConfig` |
| `management.influx.metrics.export.uri`     | `http://localhost:8086` | Overridden in compose    |
| `management.influx.metrics.export.enabled` | `false`                 | Compose sets to `true`   |

## Metrics

| Metric                         | Type    | Tags                               |
|--------------------------------|---------|------------------------------------|
| `conversion.requests.total`    | counter | `sourceCurrency`, `targetCurrency` |
| `conversion.validation.errors` | counter | `field`                            |
| `swop.errors.total`            | counter | —                                  |
| `swop.response.time`           | timer   | percentiles 0.5 / 0.95 / 0.99     |
| `cache.hit.rate`               | gauge   | —                                  |

## Project layout

```
src/main/java/com/nosto/currencyconverter/
  CurrencyConverterApplication.java
  controller/
    CurrencyConversionController.java     — POST /api/convert, GET /api/currencies
    GlobalExceptionHandler.java           — @RestControllerAdvice
  service/
    CurrencyConversionService.java        — normalise, short-circuit, cross-rate, format
    ExchangeRateProvider.java             — @Cacheable wrapper around SwopClient
    CurrencyService.java                  — @Cacheable wrapper for currency catalogue
    ConversionMetrics.java                — Micrometer counters / timer / gauge
  client/
    SwopClient.java                       — RestTemplate calls to swop.cx
    UnknownCurrencyException.java         — 422
    ExchangeRateUnavailableException.java — 503
  model/
    ConversionRequest.java
    ConversionResponse.java
    SwopRateResponse.java
    SwopCurrencyResponse.java
    CurrencyInfo.java
    ErrorResponse.java
  config/
    CacheConfig.java                      — two caches: rates 5min, currencies 24h
    HttpClientConfig.java                 — RestTemplate with timeouts
    WebMvcConfig.java                     — CORS for localhost:5173

src/test/java/com/nosto/currencyconverter/
  service/CurrencyConversionServiceTest.java
  service/CurrencyServiceTest.java
  client/SwopClientTest.java
  controller/CurrencyConversionControllerIntegrationTest.java

frontend/
  src/App.vue
  src/main.js
  index.html
  vite.config.js
  package.json
```

## Design decisions

**swop.cx free tier only supports EUR as base currency.** Every upstream
call uses `GET /rest/rates/EUR/{code}`. For non-EUR pairs the effective
rate is calculated locally as `eurToTarget / eurToSource`. The caller
doesn't need to know about this.

**Cache key is the currency code, not the pair.** I cache EUR/USD and
EUR/GBP separately rather than USD/GBP as one entry. That way if you
convert USD→GBP and then USD→JPY, the EUR/USD rate is already cached
for the second request. At most N entries for N currencies instead of
N×N for every possible pair.

**`ExchangeRateProvider` is a separate bean from `CurrencyConversionService`.**
Spring's `@Cacheable` works through AOP proxies and doesn't intercept
calls within the same bean. Splitting it out ensures the cache is
actually used.

**Unknown currency codes return 422, not 503.** If swop.cx returns 404
for an unknown code, that's a client error (bad input), not an upstream
failure. The distinction matters for the caller to know whether to retry
or fix their request.

**`BigDecimal` everywhere, no `double`.** Floating point can't represent
0.10 exactly. For money that's not acceptable.

**`Currency.getDefaultFractionDigits()` for formatting.** JPY has 0
decimal places, some currencies have 3. Hardcoding 2 gives wrong results.

**`GET /api/currencies` proxied through the backend.** The frontend needs
a currency list for the dropdowns. Fetching it directly from swop.cx in
the browser would expose the API key. The backend proxies it and caches
it for 24 hours since the list rarely changes.

## What I would do with more time

- Multi-stage Docker build to keep the image smaller
- Redis instead of Caffeine if the app were horizontally scaled
- Circuit breaker with Resilience4j — if swop.cx is down, return the
  last cached rate rather than an error
- Rate limiting on the API endpoints
