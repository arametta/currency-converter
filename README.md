# Currency Converter

A production-quality REST API in Java + Spring Boot that converts a monetary
amount from one currency to another using live exchange rates from
[swop.cx](https://swop.cx/).

Built as a technical assignment for a Nosto Software Team Lead interview.

## Stack

| Concern         | Choice                                  |
| --------------- | --------------------------------------- |
| Language        | Java 21                                 |
| Framework       | Spring Boot 3.4.1                       |
| Build           | Maven (wrapper bundled — `./mvnw`)      |
| HTTP client     | `RestTemplate`                          |
| Cache           | Caffeine (in-memory, 5-min TTL)         |
| Validation      | Jakarta Bean Validation                 |
| Tests           | JUnit 5 + Mockito + WireMock            |
| Metrics         | Micrometer → InfluxDB 1.8 → Grafana     |

## Endpoint

`POST /api/convert`

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

### Status codes

| Code | Meaning                                                    |
| ---- | ---------------------------------------------------------- |
| 200  | Successful conversion                                      |
| 400  | Validation failure (negative amount, bad currency length…) |
| 422  | Currency code is syntactically valid but not ISO 4217      |
| 503  | swop.cx is unreachable or returned an error                |
| 500  | Unhandled server error                                     |

## Building and running

### Local (requires the `SWOP_API_KEY` env var)

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

### Full monitoring stack (app + InfluxDB + Grafana)

```bash
export SWOP_API_KEY=your_key
./mvnw clean package -DskipTests
docker compose up --build
```

| Service       | URL                          |
| ------------- | ---------------------------- |
| API           | http://localhost:8080        |
| Grafana       | http://localhost:3000 (admin/admin) |
| InfluxDB API  | http://localhost:8086        |

The Grafana datasource and the `Currency Converter` dashboard are auto-provisioned.

## Usage with curl

Assumes the app is running on `http://localhost:8080`.

```bash
# EUR to USD (direct EUR base)
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100.00, "sourceCurrency": "EUR", "targetCurrency": "USD" }'

# USD to EUR (inverse of EUR base)
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "EUR" }'

# USD to GBP (cross-rate via EUR)
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 100.00, "sourceCurrency": "USD", "targetCurrency": "GBP" }'

# Same currency — short-circuits without calling swop.cx
curl -sS -X POST http://localhost:8080/api/convert \
  -H 'Content-Type: application/json' \
  -d '{ "amount": 42.50, "sourceCurrency": "USD", "targetCurrency": "USD" }'

# Lowercase codes — silently normalised to uppercase
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
```

### Actuator endpoints

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8080/actuator/metrics | jq
curl -sS http://localhost:8080/actuator/metrics/cache.hit.rate | jq
curl -sS http://localhost:8080/actuator/metrics/swop.response.time | jq
```

### InfluxDB inspection

Useful when a Grafana panel is empty — check whether the data is actually
reaching InfluxDB before suspecting the dashboard query.

```bash
# Sanity check: Influx is up
curl -sS http://localhost:8086/ping -i | head -3

# All measurements published by the app
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SHOW MEASUREMENTS' | jq

# Fields and tags on a specific measurement
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SHOW FIELD KEYS FROM "conversion_requests_total"' | jq

curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SHOW TAG KEYS FROM "conversion_requests_total"' | jq

# Latest count per currency pair
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SELECT last("value") FROM "conversion_requests_total" GROUP BY "sourceCurrency","targetCurrency"' | jq

# swop.cx call latency percentiles (separate measurement, tagged by phi)
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SELECT last("value") FROM "swop_response_time_percentile" GROUP BY "phi"' | jq

# Current Caffeine cache hit rate
curl -sS -G http://localhost:8086/query \
  --data-urlencode 'db=currency_converter' \
  --data-urlencode 'q=SELECT last("value") FROM "cache_hit_rate"' | jq
```

Notes:
- Field name is `value` for counters and gauges (Micrometer convention).
  Timers use `count`, `sum`, `mean`, `upper`; percentiles are written
  to a separate `<timer>_percentile` measurement tagged by `phi`.
- Metric names with dots in code (e.g. `conversion.requests.total`)
  become underscores in Influx (`conversion_requests_total`).

## API constraints

**swop.cx free tier:** The free plan only supports EUR as the base
currency. This API handles non-EUR source currencies transparently
using cross-rate calculation: both the source and target EUR-based
rates are fetched independently and the effective source→target rate
is derived from their ratio. The caller does not need to be aware of
this implementation detail.

## Tests

```bash
./mvnw test
```

21 tests across:

- `CurrencyConversionServiceTest` — service logic + Jakarta validation
- `SwopClientTest` — HTTP-failure translation (Mockito over `RestTemplate`)
- `CurrencyConversionControllerIntegrationTest` — full `@SpringBootTest` against WireMock-stubbed swop.cx

WireMock stands in for swop.cx in integration tests, so tests are hermetic
(no network, no real API key).

## Configuration

All settings live in `src/main/resources/application.properties` and are
overridable via env vars (Spring's relaxed binding). Notable ones:

| Property                                       | Default                  | Notes                          |
| ---------------------------------------------- | ------------------------ | ------------------------------ |
| `swop.api-key`                                 | `${SWOP_API_KEY}`        | Required at runtime            |
| `swop.base-url`                                | `https://swop.cx`        |                                |
| `spring.cache.caffeine.spec`                   | `expireAfterWrite=5m`    | TTL for cached rates           |
| `management.influx.metrics.export.uri`         | `http://localhost:8086`  | Override in compose            |
| `management.influx.metrics.export.enabled`     | `false`                  | Compose sets this to `true`    |

## Metrics

Exposed at `/actuator/metrics` and pushed to InfluxDB:

| Metric                          | Type      | Tags                            |
| ------------------------------- | --------- | ------------------------------- |
| `conversion.requests.total`     | counter   | `sourceCurrency`, `targetCurrency` |
| `conversion.validation.errors`  | counter   | `field`                         |
| `swop.errors.total`             | counter   | —                               |
| `swop.response.time`            | timer     | percentiles 0.5 / 0.95 / 0.99   |
| `cache.hit.rate`                | gauge     | —                               |

## Project layout

```
src/main/java/com/nosto/currencyconverter/
  CurrencyConverterApplication.java   — @SpringBootApplication + @EnableCaching
  controller/
    CurrencyConversionController.java — POST /api/convert
    GlobalExceptionHandler.java       — @RestControllerAdvice (400/422/503/500)
  service/
    CurrencyConversionService.java    — normalise, short-circuit, convert, format
    ExchangeRateProvider.java         — @Cacheable boundary in front of SwopClient
    ConversionMetrics.java            — Micrometer counters / timer / gauge
    UnknownCurrencyException.java     — 422
  client/
    SwopClient.java                   — RestTemplate adapter for swop.cx
    ExchangeRateUnavailableException.java — 503
  model/
    ConversionRequest.java            — request DTO + Jakarta annotations
    ConversionResponse.java           — response DTO
    SwopRateResponse.java             — swop.cx JSON binding
    ErrorResponse.java                — uniform error envelope
  config/
    CacheConfig.java                  — Caffeine, 5-min TTL, max 500, stats on
    HttpClientConfig.java             — RestTemplate w/ timeouts

src/test/java/com/nosto/currencyconverter/
  service/CurrencyConversionServiceTest.java
  client/SwopClientTest.java
  controller/CurrencyConversionControllerIntegrationTest.java
```

## Design notes (for interview discussion)

- **Why `ExchangeRateProvider` is a separate bean.** Spring's `@Cacheable`
  is implemented via AOP proxies, which don't intercept self-invocations
  inside the same bean. Putting the cached method in a separate Spring
  bean ensures every call goes through the proxy and the cache works.
- **Why `BigDecimal` everywhere.** `double` cannot represent `0.10` exactly;
  rounding errors compound across multiplications and we lose money.
- **Why `Currency.getDefaultFractionDigits()` instead of hardcoding 2.**
  JPY has 0 decimals, BHD/KWD/JOD have 3. Hardcoding produces wrong values.
- **Why the unknown-currency check happens before swop.cx.** Cheaper
  (no upstream call wasted on a known-bad input), and gives a deterministic
  422 regardless of how swop.cx happens to respond to unknown codes.
- **Why `WireMockServer` directly, not `WireMockExtension`.** The extension
  starts WireMock in `@BeforeAll`, which runs *after* Spring builds the
  application context. Manual lifecycle in `@BeforeAll` + a
  `@DynamicPropertySource` lambda guarantees Spring sees the right port.
- **Cardinality of `conversion.requests.total`.** Tagging by both source
  and target currency creates up to ~36k series. Acceptable for InfluxDB
  at interview-demo volumes; would aggregate or drop tags at real scale.
