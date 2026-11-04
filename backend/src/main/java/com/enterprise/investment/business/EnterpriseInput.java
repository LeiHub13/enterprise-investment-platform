package com.enterprise.investment.business;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

record EnterpriseInput(
        @Size(max = 40) String uscc,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 50) String enterpriseType,
        @Size(max = 12) String provinceCode,
        @Size(max = 12) String cityCode,
        @Size(max = 12) String districtCode,
        @Size(max = 20) String industryCode,
        @Size(max = 100) String industryName,
        @Digits(integer = 16, fraction = 2) @DecimalMin("0") BigDecimal registeredCapital,
        LocalDate establishedDate,
        @Size(max = 30) String businessStatus,
        @Size(max = 500) String registeredAddress,
        @Size(max = 15000) String businessScope,
        @Size(max = 500) String website,
        LocalDateTime lastVerifiedAt
) {}
