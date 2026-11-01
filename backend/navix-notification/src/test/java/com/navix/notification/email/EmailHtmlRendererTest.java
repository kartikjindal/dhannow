package com.navix.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The branded HTML wrapper: structure, brand, escaping, and paragraph handling. */
class EmailHtmlRendererTest {

    private final EmailHtmlRenderer renderer = new EmailHtmlRenderer(new com.navix.common.brand.BrandProperties("DhanNow", "www.dhannow.com", "9211331169", "support@dhannow.com"));

    @Test
    void rendersBrandedShellWithSubjectAndBody() {
        String html = renderer.render("Your loan is approved", "Hi Asha,\n\nGood news.");

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("DhanNow");                         // wordmark
        assertThat(html).contains("#0C2540");                       // brand navy
        assertThat(html).contains("Your loan is approved");         // subject in <title>/<h1>
        assertThat(html).contains("Good news.");                    // body
        assertThat(html).contains("DhanNow");                 // footer
    }

    @Test
    void escapesHtmlInSubjectAndBody() {
        String html = renderer.render("<script>x</script>", "1 < 2 & 3 > 0");

        assertThat(html).doesNotContain("<script>x</script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("1 &lt; 2 &amp; 3 &gt; 0");
    }

    @Test
    void splitsBlankLineSeparatedBlocksIntoParagraphs() {
        String html = renderer.render("S", "para one\n\npara two");

        assertThat(html).contains("<p style=\"margin:0 0 14px;\">para one</p>");
        assertThat(html).contains("<p style=\"margin:0 0 14px;\">para two</p>");
    }

    @Test
    void handlesNullBody() {
        String html = renderer.render("S", null);
        assertThat(html).contains("<!DOCTYPE html>");
    }

    @Test
    void rendersOtpCodeInBrandedShell() {
        String html = renderer.renderOtp("Verify your email address", "Use this code:", "482913", 10);
        assertThat(html).contains("Verify your email address").contains(">482913</td>")
                .contains("10 minutes").contains("Never share this code");
    }

    @Test
    void escapesOtpInputs() {
        String html = renderer.renderOtp("<b>", "<i>", "<x>", 10);
        assertThat(html).doesNotContain("<b>").doesNotContain("<i>").doesNotContain("<x>");
    }
}
