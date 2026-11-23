package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

@Service
public class MessageTemplateService {

    private static final ZoneId SWISS_ZONE = ZoneId.of("Europe/Zurich");
    private static final DateTimeFormatter SWISS_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MessageSource messageSource;
    private final AppProperties appProperties;

    public MessageTemplateService(MessageSource messageSource, AppProperties appProperties) {
        this.messageSource = messageSource;
        this.appProperties = appProperties;
    }

    public String loginSubject(LanguageCode language) {
        return message(language, "message.login.subject");
    }

    public String loginBody(LanguageCode language, String loginLink) {
        return String.join("\n\n",
                message(language, "message.greeting"),
                message(language, "message.login.intro"),
                message(language, "message.login.link", loginLink),
                message(language, "message.login.ignore"));
    }

    public String accountDeletionSubject(LanguageCode language) {
        return message(language, "message.account-deletion.subject");
    }

    public String accountDeletionBody(LanguageCode language, String confirmLink) {
        return String.join("\n\n",
                message(language, "message.greeting"),
                message(language, "message.account-deletion.intro"),
                message(language, "message.account-deletion.link", confirmLink),
                message(language, "message.account-deletion.ignore"),
                generalFooter(language));
    }

    public String bookingConfirmationSubject(LanguageCode language) {
        return message(language, "message.booking-confirmation.subject");
    }

    public String bookingConfirmationBody(LanguageCode language, Slot slot, String manageUrl) {
        Map<String, String> details = bookingDetails(language, slot.getEinAb(), slotTeacher(slot).getName(), slotTeacher(slot).getPhoneNumber());
        return String.join("\n\n",
                message(language, "message.greeting.named", slot.getBookingUser().getName()),
                message(language, "message.booking-confirmation.intro"),
                formatDetails(details),
                message(language, "message.booking-confirmation.link", manageUrl),
                bookingFooter(language));
    }

    public String einAbUpdatedSubject(LanguageCode language) {
        return message(language, "message.einab-updated.subject");
    }

    public String einAbUpdatedBody(LanguageCode language, Slot slot) {
        Map<String, String> details = bookingDetails(language, slot.getEinAb(), slotTeacher(slot).getName(), slotTeacher(slot).getPhoneNumber());
        return String.join("\n\n",
                message(language, "message.greeting.named", slot.getBookingUser().getName()),
                message(language, "message.einab-updated.intro"),
                formatDetails(details),
                bookingFooter(language));
    }

    public String teacherCancellationSubject(LanguageCode language) {
        return message(language, "message.teacher-cancellation.subject");
    }

    public String teacherCancellationBody(LanguageCode language, Slot slot, String manageUrl) {
        Map<String, String> details = bookingDetails(language, slot.getEinAb(), slotTeacher(slot).getName(), slotTeacher(slot).getPhoneNumber());
        return String.join("\n\n",
                message(language, "message.greeting.named", slot.getBookingUser().getName()),
                message(language, "message.teacher-cancellation.intro"),
                formatDetails(details),
                message(language, "message.teacher-cancellation.link", manageUrl),
                bookingFooter(language));
    }

    /** Sent to a booked person when an admin removes them or deletes the whole EinAb. */
    public String adminBookingRemovalBody(LanguageCode language, Slot slot, String adminName, boolean einAbDeleted, String manageUrl) {
        Map<String, String> details = bookingDetails(language, slot.getEinAb(), slotTeacher(slot).getName(), slotTeacher(slot).getPhoneNumber());
        return String.join("\n\n",
                message(language, "message.greeting.named", slot.getBookingUser().getName()),
                message(language, einAbDeleted ? "message.admin-booking-removal.einab-deleted" : "message.admin-booking-removal.intro", adminName),
                formatDetails(details),
                message(language, "message.teacher-cancellation.link", manageUrl),
                bookingFooter(language));
    }

    public String adminEinAbDeletedSubject(LanguageCode language) {
        return message(language, "message.admin-einab-deleted.subject");
    }

    public String adminEinAbDeletedBody(LanguageCode language, EinAb einAb, String adminName, String reason) {
        User teacher = einAb.getTeacher();
        Map<String, String> details = bookingDetails(language, einAb, teacher.getName(), teacher.getPhoneNumber());
        return String.join("\n\n",
                message(language, "message.greeting.named", teacher.getName()),
                message(language, "message.admin-einab-deleted.intro", adminName),
                formatDetails(details),
                message(language, "message.admin-einab-deleted.reason", reason),
                generalFooter(language));
    }

    public String teacherBookingConfirmationSubject(LanguageCode language) {
        return message(language, "message.teacher-booking-confirmation.subject");
    }

    public String teacherBookingConfirmationBody(LanguageCode language, Slot slot, String teacherDashboardUrl) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(message(language, "message.details.booking-user-name"), valueOrDash(slot.getBookingUser().getName()));
        details.put(message(language, "message.details.foodsharing-id"), slot.getBookingUser().getFoodsharingId());
        details.put(message(language, "message.details.booking-user-phone"), valueOrDash(slot.getBookingUser().getPhoneNumber()));
        putBusinessName(language, details, slot.getEinAb());
        details.put(message(language, "message.details.category"), categoryLabel(language, slot.getEinAb().getCategory()));
        details.put(message(language, "message.details.start"), swissDateTime(slot.getEinAb().getStartDateTime()));
        return String.join("\n\n",
                message(language, "message.greeting.named", slotTeacher(slot).getName()),
                message(language, "message.teacher-booking-confirmation.intro", slot.getBookingUser().getName()),
                formatDetails(details),
                message(language, "message.teacher-booking-confirmation.link", teacherDashboardUrl),
                generalFooter(language));
    }

    public String teacherBookingCancellationSubject(LanguageCode language) {
        return message(language, "message.teacher-booking-cancellation.subject");
    }

    public String teacherBookingCancellationBody(LanguageCode language, Slot slot) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(message(language, "message.details.booking-user-name"), valueOrDash(slot.getBookingUser().getName()));
        details.put(message(language, "message.details.foodsharing-id"), slot.getBookingUser().getFoodsharingId());
        putBusinessName(language, details, slot.getEinAb());
        details.put(message(language, "message.details.category"), categoryLabel(language, slot.getEinAb().getCategory()));
        details.put(message(language, "message.details.start"), swissDateTime(slot.getEinAb().getStartDateTime()));
        return String.join("\n\n",
                message(language, "message.greeting.named", slotTeacher(slot).getName()),
                message(language, "message.teacher-booking-cancellation.intro", slot.getBookingUser().getName()),
                formatDetails(details),
                generalFooter(language));
    }

    public String teacherCommentReminderSubject(LanguageCode language) {
        return message(language, "message.teacher-comment-reminder.subject");
    }

    /** One reminder per teacher, listing every booking of theirs that still has no comment. */
    public String teacherCommentReminderBody(LanguageCode language, User teacher, java.util.List<Slot> slots, String teacherUrl) {
        String items = slots.stream()
                .map(slot -> message(language, "message.teacher-comment-reminder.item",
                        slot.getBookingUser().getName(),
                        slot.getBookingUser().getFoodsharingId(),
                        swissDateTime(slot.getEinAb().getStartDateTime()),
                        categoryLabel(language, slot.getEinAb().getCategory())))
                .collect(Collectors.joining("\n"));
        return String.join("\n\n",
                message(language, "message.greeting.named", teacher.getName()),
                message(language, slots.size() == 1
                        ? "message.teacher-comment-reminder.intro"
                        : "message.teacher-comment-reminder.intro.many") + "\n" + items,
                message(language, "message.teacher-comment-reminder.link", teacherUrl),
                message(language, "message.teacher-comment-reminder.outro"),
                generalFooter(language));
    }

    public String swissDateTime(Instant value) {
        return value == null ? "" : SWISS_DATE_TIME.format(value.atZone(SWISS_ZONE));
    }

    private Map<String, String> bookingDetails(LanguageCode language, EinAb einAb, String teacherName, String teacherPhone) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(message(language, "message.details.teacher"), teacherName);
        details.put(message(language, "message.details.teacher-phone"), valueOrDash(teacherPhone));
        details.put(message(language, "message.details.category"), categoryLabel(language, einAb.getCategory()));
        details.put(message(language, "message.details.start"), swissDateTime(einAb.getStartDateTime()));
        if (einAb.getCategory() == EinAbCategory.ONLINE) {
            details.put(message(language, "message.details.online-call-link"), valueOrDash(einAb.getOnlineCallLink()));
        } else {
            details.put(message(language, "message.details.location"), valueOrDash(einAb.getLocation()));
            details.put(message(language, "message.details.public-location"), valueOrDash(einAb.getPublicLocation()));
        }
        details.put(message(language, "message.details.public-info"), valueOrDash(einAb.getPublicInfo()));
        details.put(message(language, "message.details.private-info"), valueOrDash(einAb.getPrivateInfo()));
        details.put(message(language, "message.details.fairteiler"), message(language, einAb.isVisitFairteiler() ? "message.yes" : "message.no"));
        return details;
    }

    private void putBusinessName(LanguageCode language, Map<String, String> details, EinAb einAb) {
        String businessName = einAb.getBusinessName();
        if (businessName != null && !businessName.isBlank()) {
            details.put(message(language, "message.details.business-name"), businessName);
        }
    }

    public String supportRequestSubject(LanguageCode language, ch.it4user.foodsharing.domain.enumtype.SupportRequestCategory category) {
        return message(language, "message.support-request.subject." + category.name().toLowerCase(Locale.ROOT));
    }

    public String supportRequestBody(LanguageCode language, ch.it4user.foodsharing.domain.entity.SupportRequest request,
            String bezirkName, String supportUrl) {
        String sender = request.getContactName() == null
                ? request.getContactFoodsharingId()
                : request.getContactName() + " (ID " + request.getContactFoodsharingId() + ")";
        String senderLine = request.getUser() == null
                ? message(language, "message.support-request.sender.unverified", sender)
                : message(language, "message.support-request.sender", sender);
        String context = message(language, "message.support-request.context",
                valueOrDash(bezirkName), valueOrDash(request.getClientVersion()),
                request.getPlatform() == null ? "-" : message(language, "message.support-request.platform." + request.getPlatform().name().toLowerCase(Locale.ROOT)));
        return String.join("\n\n",
                message(language, "message.greeting"),
                senderLine,
                request.getMessage(),
                context,
                message(language, "message.support-request.link", supportUrl),
                message(language, "message.support-request.reply", "https://foodsharing.network/user/" + request.getContactFoodsharingId() + "/profile"));
    }

    /** For the booked person: automatic message, ask the EinAb giver or use the help page. */
    private String bookingFooter(LanguageCode language) {
        return message(language, "message.footer.booking", helpUrl());
    }

    /** For the EinAb giver and other messages without an EinAb giver to ask: automatic message, use the help page. */
    private String generalFooter(LanguageCode language) {
        return message(language, "message.footer.general", helpUrl());
    }

    private String helpUrl() {
        return appProperties.getFrontend().getBaseUrl().replaceFirst("/+$", "") + "/help";
    }

    private String formatDetails(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.joining("\n"));
    }

    private String categoryLabel(LanguageCode language, EinAbCategory category) {
        return message(language, "message.category." + category.name().toLowerCase(Locale.ROOT));
    }

    private String message(LanguageCode language, String key, Object... arguments) {
        return messageSource.getMessage(key, arguments, Locale.forLanguageTag(language.getCode()));
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private User slotTeacher(Slot slot) {
        return slot.getTeacher() == null ? slot.getEinAb().getTeacher() : slot.getTeacher();
    }
}
