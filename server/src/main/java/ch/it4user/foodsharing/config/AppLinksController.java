package ch.it4user.foodsharing.config;

import ch.it4user.foodsharing.service.AppProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets einab.ch links (e.g. the sign-in link in the Foodsharing message) open in the store app instead of the browser:
 * Android App Links (assetlinks.json) and iOS Universal Links (apple-app-site-association). Both answer 404 until the
 * signing fingerprints / app IDs are configured (APP_ANDROID_SHA256_FINGERPRINTS, APP_IOS_APP_IDS).
 */
@RestController
public class AppLinksController {

    /** Pages that open in the app. Everything else on einab.ch (API, files) stays in the browser. */
    private static final String APP_PATH_PREFIX = "/bezirke/";

    private final AppProperties.Push push;

    public AppLinksController(AppProperties appProperties) {
        this.push = appProperties.getPush();
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> androidAssetLinks() {
        List<String> fingerprints = clean(push.getAndroidSha256Fingerprints());
        if (fingerprints.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1))).body(List.of(Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of(
                        "namespace", "android_app",
                        "package_name", push.getAndroidPackageName(),
                        "sha256_cert_fingerprints", fingerprints))));
    }

    @GetMapping(value = {"/.well-known/apple-app-site-association", "/apple-app-site-association"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        List<String> appIds = clean(push.getIosAppIds());
        if (appIds.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1))).body(Map.of(
                "applinks", Map.of("details", List.of(Map.of(
                        "appIDs", appIds,
                        "components", List.of(Map.of("/", APP_PATH_PREFIX + "*"))))),
                "webcredentials", Map.of("apps", appIds)));
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
