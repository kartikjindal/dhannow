package com.navix.app.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * EXT-30 (PLAN Phase 6, extension item 10): extension money is never counted as recovery. Every statement in the
 * backend's main code that reads the {@code payment} table ({@code from payment} / {@code join payment}, SQL or
 * JPQL) must say which purpose it means: a {@code purpose} filter or column, an {@code extension_id} filter, or a
 * comment naming both purposes. A statement is the Java text between semicolons, so a query and the comment just
 * above it are read together; each read needs its own mention. Deletes are not reads.
 */
class PaymentPurposeGuardTest {

    private static final Pattern READ = Pattern.compile("(?i)\\b(from|join)\\s+payment\\b");
    private static final Pattern PURPOSE = Pattern.compile("(?i)purpose|extension_id");
    private static final Pattern BLOCK_OPEN = Pattern.compile("\\{\\s*\\R");

    @Test
    void ext30_everyQueryReadingPaymentsSaysWhichPurpose() throws IOException {
        Path backend = Path.of("").toAbsolutePath().getParent();
        List<String> failures = new ArrayList<>();
        int reads = 0;
        try (Stream<Path> files = Files.walk(backend)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/")).toList()) {
                String source = read(f);
                reads += READ.matcher(source).results().count();
                unguarded(source).forEach(s -> failures.add(backend.relativize(f) + ": " + s));
            }
        }
        assertThat(reads).as("payment reads found (is the scan looking in the right place?)").isGreaterThan(20);
        assertThat(failures).as("queries reading payment with no purpose handling").isEmpty();
    }

    @Test
    void theGuardCatchesAQueryWithoutPurpose() {
        assertThat(unguarded("int a = jdbc.query(\"select sum(p.amount) from payment p where p.status = 'VERIFIED'\");"))
                .hasSize(1);
        assertThat(unguarded("int a = jdbc.query(\"select sum(p.amount) from payment p where p.purpose = 'REPAYMENT'\");"))
                .isEmpty();
        // Two reads, one purpose: the second read is unguarded.
        assertThat(unguarded("x = q(\"select 1 from payment p where p.purpose = 'REPAYMENT' union select 1 from payment\");"))
                .hasSize(1);
        assertThat(unguarded("jdbc.update(\"DELETE FROM payment WHERE loan_id = ?\", id);")).isEmpty();
        assertThat(unguarded("x = q(\"select 1 from payment_allocation a\");")).isEmpty();
    }

    /** The statements in {@code source} with more payment reads than purpose mentions. */
    static Set<String> unguarded(String source) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = READ.matcher(source);
        while (m.find()) {
            String before = source.substring(Math.max(0, m.start() - 7), m.start()).toLowerCase(Locale.ROOT);
            if (before.endsWith("delete ")) {
                continue;
            }
            int start = Math.max(source.lastIndexOf(';', m.start()), lastBlockOpen(source, m.start())) + 1;
            int end = source.indexOf(';', m.end());
            String statement = source.substring(start, end < 0 ? source.length() : end);
            long reads = READ.matcher(statement).results()
                    .filter(r -> !statement.substring(Math.max(0, r.start() - 7), r.start())
                            .toLowerCase(Locale.ROOT).endsWith("delete "))
                    .count();
            if (PURPOSE.matcher(statement).results().count() < reads) {
                out.add(statement.strip().replaceAll("\\s+", " "));
            }
        }
        return out;
    }

    private static int lastBlockOpen(String source, int before) {
        Matcher b = BLOCK_OPEN.matcher(source.substring(0, before));
        int last = -1;
        while (b.find()) {
            last = b.start();
        }
        return last;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
