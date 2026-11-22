package com.enterprise.investment.business;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RegionController {
    private final RegionCatalog regions;

    RegionController(RegionCatalog regions) {
        this.regions = regions;
    }

    @GetMapping("/api/regions")
    List<RegionCatalog.Region> list() {
        return regions.all();
    }
}
