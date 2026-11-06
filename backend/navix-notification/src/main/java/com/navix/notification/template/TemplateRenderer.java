package com.navix.notification.template;

import com.navix.common.brand.BrandProperties;
import com.navix.common.brand.GrievanceProperties;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.catalog.NotificationType;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders a {@code (type, channel)} template against a model by substituting {@code {key}} tokens.
 * Generalises {@code BorrowerOtpService.buildMessage}'s {@code {otp}}/{@code {ttl}} approach. An
 * unknown/absent key renders as {@code —} (never leaks a raw {@code {placeholder}} to a recipient).
 */
@Component
public class TemplateRenderer {

    /** Joins a WhatsApp template's ordered parameter values in {@link RenderedMessage#body()}. */
    public static final String WHATSAPP_PARAM_SEPARATOR = "\u001F";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    private final NotificationTemplates templates;
    /** The brand and grievance tokens every template may use ({brand}, {brandWebsite}, {grievanceName}…). */
    private final Map<String, Object> brandModel;

    public TemplateRenderer(NotificationTemplates templates, BrandProperties brand, GrievanceProperties grievance) {
        this.templates = templates;
        this.brandModel = brandModel(brand, grievance);
    }

    /** One brand config (CLAUDE.md): the copy never names the brand itself. */
    public static Map<String, Object> brandModel(BrandProperties brand, GrievanceProperties grievance) {
        Map<String, Object> m = new HashMap<>();
        m.put("brand", brand.name());
        m.put("brandWebsite", brand.website());
        m.put("supportPhone", brand.supportPhone());
        m.put("supportEmail", brand.supportEmail());
        m.put("grievanceName", grievance.officerName());
        m.put("grievancePhone", grievance.officerPhone());
        m.put("grievanceEmail", grievance.officerEmail());
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Render the template for {@code type}+{@code channel}, or {@code null} if none is defined. */
    public RenderedMessage render(NotificationType type, NotificationChannel channel, Map<String, Object> model) {
        TemplateDef def = templates.get(type, channel);
        if (def == null) {
            return null;
        }
        Map<String, Object> merged = new HashMap<>(brandModel);
        if (model != null) {
            merged.putAll(model);
        }
        model = merged;
        String subject = substitute(def.subject(), model);
        String body = substitute(def.body(), model);
        if (channel == NotificationChannel.SMS) {
            // SMS: render amounts as "Rs." not "₹" — the ₹ glyph is outside GSM-7 and would force the
            // whole SMS to UCS-2 (70 chars/segment, ~double cost). In-app/email keep "₹". The symbol
            // only ever sits inside a DLT {#var#} value, so this never breaks template matching.
            body = body == null ? null : body.replace("₹", "Rs. ");
            // Carry the NotificationType name so the gateway resolves this type's DLT Template ID.
            return new RenderedMessage(channel, subject, body, type.name());
        }
        if (channel == NotificationChannel.PUSH) {
            // The type rides along as the push's data so the app can route the tap (CC-53).
            return new RenderedMessage(channel, subject, body, type.name());
        }
        return new RenderedMessage(channel, subject, body, null);
    }

    static String substitute(String template, Map<String, Object> model) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = model == null ? null : model.get(m.group(1));
            String text = value == null ? "—" : String.valueOf(value);
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }
}
