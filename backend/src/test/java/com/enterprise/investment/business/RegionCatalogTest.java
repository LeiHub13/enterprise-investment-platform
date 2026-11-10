package com.enterprise.investment.business;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionCatalogTest {
    private final RegionCatalog regions = new RegionCatalog();

    RegionCatalogTest() throws java.io.IOException {
    }

    @Test
    void resolvesNamesAndRetainsLegacyCodes() {
        assertEquals("江苏省", regions.nameOrLegacyCode("江苏", "province", null));
        assertEquals("南京市", regions.nameOrLegacyCode("南京", "city", "江苏省"));
        assertEquals("320000", regions.nameOrLegacyCode("320000", "province", null));
        assertEquals("320102", regions.code("玄武区", "district", "南京市"));
        assertTrue(regions.variants("江苏省", "province", null).containsAll(
                java.util.List.of("江苏省", "江苏", "32", "320000", "320000000000")));
        assertTrue(regions.variants("320100", "city", "江苏省").containsAll(
                java.util.List.of("南京市", "南京", "3201", "320100")));
    }

    @Test
    void handlesMunicipalitiesAndProvinceAdministeredCities() {
        assertEquals("110100", regions.code("北京市", "city", "北京市"));
        assertEquals("110100", regions.code("110000", "city", "北京市"));
        assertEquals("110105", regions.code("朝阳区", "district", "北京市"));
        assertEquals("659001", regions.code("石河子市", "city", "新疆"));
        assertEquals("659001", regions.code("石河子市", "city", "650000"));
        assertEquals("自定义地区", regions.nameOrLegacyCode("自定义地区", "city", null));
    }
}
