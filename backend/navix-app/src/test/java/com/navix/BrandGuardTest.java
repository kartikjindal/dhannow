package com.navix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * PLAN Phase 7 brand sweep: the navix brand name never appears in main code or config. Copy takes the brand from
 * {@code navix.brand.*}. Historical Flyway migrations are left as they ran.
 */
class BrandGuardTest {

    /** Spelled in two halves so this file is not itself a match. */
    static final String OLD_BRAND = "Dhan" + "Boost";

    @Test
    void theOldBrandNameIsNotInMainCodeOrConfig() throws IOException {
        Path backend = Path.of("").toAbsolutePath().getParent();
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(backend)) {
            for (Path f : files.filter(Files::isRegularFile).filter(BrandGuardTest::inScope).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(OLD_BRAND)) {
                        hits.add(backend.relativize(f) + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(scanned).as("files scanned (is the scan looking in the right place?)").isGreaterThan(500);
        assertThat(hits).as("the old brand name in src/main").isEmpty();
    }

    static boolean inScope(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/src/main/") && !s.contains("/db/migration/") && !s.contains("/target/")
                && (s.endsWith(".java") || s.endsWith(".yml") || s.endsWith(".yaml") || s.endsWith(".xml")
                || s.endsWith(".properties") || s.endsWith(".html") || s.endsWith(".txt"));
    }

    @Test
    void theScopeSkipsMigrationsAndTests() {
        assertThat(inScope(Path.of("/b/navix-app/src/main/resources/db/migration/V38__x.sql"))).isFalse();
        assertThat(inScope(Path.of("/b/navix-app/src/test/java/X.java"))).isFalse();
        assertThat(inScope(Path.of("/b/navix-app/src/main/resources/application.yml"))).isTrue();
        assertThat(inScope(Path.of("/b/navix-loan/src/main/java/com/navix/X.java"))).isTrue();
    }
}
