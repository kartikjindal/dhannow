package com.navix.config;

import com.navix.common.brand.BrandProperties;
import com.navix.common.brand.GrievanceProperties;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.web.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/app/config} (PLAN Phase 8): read by the app at launch, before sign-in. Below {@code minVersion}
 * the app shows the blocking Update screen; {@code maintenance} shows the banner and goes read-only (CC-52).
 * Contacts come from the one brand config, never from the app's own copy.
 */
@RestController
@RequiredArgsConstructor
public class AppConfigController {

    public record Contact(String name, String phone, String email) {
    }

    public record Legal(String key, String title, String url, String version) {
    }

    public record AppConfig(String minVersion, String latestVersion, boolean maintenance, Map<String, Boolean> flags,
                            String brand, String website, Contact support, Contact grievance, List<Legal> legal) {
    }

    private final MobileAppProperties app;
    private final BrandProperties brand;
    private final GrievanceProperties grievance;
    private final FeatureFlagService flags;

    @GetMapping("/api/app/config")
    public ApiResponse<AppConfig> config() {
        Map<String, Boolean> on = new LinkedHashMap<>();
        for (String key : app.flags()) {
            on.put(key, flags.isEnabled(key, false));
        }
        String base = app.siteBaseUrl() == null ? "" : app.siteBaseUrl().replaceAll("/+$", "");
        List<Legal> legal = app.legal().stream()
                .map(d -> new Legal(d.key(), d.title(), base + d.path(), d.version()))
                .toList();
        return ApiResponse.ok(new AppConfig(app.minVersion(), app.latestVersion(), app.maintenance(), on,
                brand.name(), brand.website(),
                new Contact(brand.name() + " support", brand.supportPhone(), brand.supportEmail()),
                new Contact(grievance.officerName(), grievance.officerPhone(), grievance.officerEmail()),
                legal));
    }
}
