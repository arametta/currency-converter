package com.nosto.currencyconverter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nosto.currencyconverter.client.SwopClient;
import com.nosto.currencyconverter.model.CurrencyInfo;
import com.nosto.currencyconverter.model.SwopCurrencyResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests for the data-shaping behaviour of CurrencyService.
 *
 * The @Cacheable behaviour is NOT exercised here — caching requires the
 * Spring AOP proxy, which isn't present in a Mockito-only test. That
 * behaviour is covered in CurrencyConversionControllerIntegrationTest
 * (currenciesEndpoint_isCachedAcrossRequests), where the full Spring context
 * is up and WireMock can count upstream calls.
 */
@ExtendWith(MockitoExtension.class)
class CurrencyServiceTest {

    @Mock SwopClient swopClient;
    @InjectMocks CurrencyService service;

    @Test
    void inactiveCurrenciesAreFilteredOut() {
        when(swopClient.getCurrencies()).thenReturn(List.of(
                new SwopCurrencyResponse("USD", "United States dollar", true),
                new SwopCurrencyResponse("XAF", "CFA franc BEAC", false),
                new SwopCurrencyResponse("EUR", "Euro", true),
                new SwopCurrencyResponse("ZWL", "Zimbabwean dollar", false)
        ));

        List<CurrencyInfo> result = service.getAvailableCurrencies();

        assertThat(result).extracting(CurrencyInfo::code)
                .containsExactly("EUR", "USD")
                .doesNotContain("XAF", "ZWL");
    }

    @Test
    void resultIsSortedAlphabeticallyByCode() {
        when(swopClient.getCurrencies()).thenReturn(List.of(
                new SwopCurrencyResponse("USD", "United States dollar", true),
                new SwopCurrencyResponse("GBP", "Pound sterling", true),
                new SwopCurrencyResponse("EUR", "Euro", true),
                new SwopCurrencyResponse("JPY", "Japanese yen", true)
        ));

        List<CurrencyInfo> result = service.getAvailableCurrencies();

        assertThat(result).extracting(CurrencyInfo::code)
                .containsExactly("EUR", "GBP", "JPY", "USD");
    }

    @Test
    void mapsSwopResponseToNarrowCurrencyInfoDto() {
        // Verifies the public DTO leaks no extra fields from the upstream
        // response (no `active` flag, no numeric_code, no decimal_digits).
        // Since CurrencyInfo is a record with exactly (code, name), any
        // accidental field exposure would be a compile error elsewhere.
        when(swopClient.getCurrencies()).thenReturn(List.of(
                new SwopCurrencyResponse("EUR", "Euro", true)
        ));

        List<CurrencyInfo> result = service.getAvailableCurrencies();

        assertThat(result).containsExactly(new CurrencyInfo("EUR", "Euro"));
    }
}
