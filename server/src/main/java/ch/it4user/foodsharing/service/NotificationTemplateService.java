package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.web.util.HtmlUtils;
import org.springframework.stereotype.Service;

/** Texts of the Foodsharing messages sent to users who subscribed to notifications about new EinAbs. */
@Service
public class NotificationTemplateService {

    private static final ZoneId SWISS_ZONE = ZoneId.of("Europe/Zurich");
    private static final DateTimeFormatter SWISS_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final MessageSource messageSource;

    private final AppProperties appProperties;

    public NotificationTemplateService(MessageSource messageSource, AppProperties appProperties) {
        this.messageSource = messageSource;
        this.appProperties = appProperties;
    }

    public String newEinAbSubject(LanguageCode language) {
        return message(language, "message.notification.new-einab.subject");
    }

    public String newEinAbBody(LanguageCode language, String userName, EinAb einAb, String bookingUrl, String unsubscribeUrl) {
        return String.join("\n",
                message(language, "message.greeting.named", userName),
                "",
                message(language, "message.notification.new-einab.intro"),
                "",
                field(language, "category") + ": " + message(language, "message.category." + einAb.getCategory().name().toLowerCase(Locale.ROOT)),
                field(language, "start") + ": " + swissDateTime(einAb),
                field(language, "location") + ": " + valueOrDash(einAb.getPublicLocation()),
                field(language, "public-info") + ": " + valueOrDash(einAb.getPublicInfo()),
                field(language, "fairteiler") + ": " + message(language, einAb.isVisitFairteiler() ? "message.yes" : "message.no"),
                "",
                message(language, "message.notification.new-einab.book", bookingUrl),
                "",
                message(language, "message.notification.new-einab.outro"),
                message(language, "message.notification.unsubscribe", unsubscribeUrl));
    }

    public String subscriptionConfirmationSubject(LanguageCode language) {
        return message(language, "message.notification.subscription.subject");
    }

    public String subscriptionConfirmationBody(LanguageCode language, String userName, String unsubscribeUrl) {
        return String.join("\n",
                message(language, "message.greeting.named", userName),
                "",
                message(language, "message.notification.subscription.intro"),
                message(language, "message.notification.subscription.outro"),
                "",
                message(language, "message.notification.unsubscribe", unsubscribeUrl),
                "",
                message(language, "message.footer.general", appProperties.getFrontend().getBaseUrl().replaceFirst("/+$", "") + "/help"));
    }

    /** The plain text of a notification as simple HTML, for the email channel. */
    public String toHtml(String text) {
        return "<html><body style=\"font-family:Arial,Helvetica,sans-serif;color:#1f2937;line-height:1.5;\">"
                + HtmlUtils.htmlEscape(text).replace("\n", "<br>") + "</body></html>";
    }

    private String swissDateTime(EinAb einAb) {
        return einAb.getStartDateTime() == null ? "" : SWISS_DATE_TIME.format(einAb.getStartDateTime().atZone(SWISS_ZONE));
    }

    private String field(LanguageCode language, String key) {
        return message(language, "message.details." + key);
    }

    private String message(LanguageCode language, String key, Object... arguments) {
        LanguageCode resolved = language == null ? LanguageCode.DE : language;
        return messageSource.getMessage(key, arguments, Locale.forLanguageTag(resolved.getCode()));
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
