package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.SlotRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reminds teachers once per booking to write their comment when the EinAb started more than 24 hours ago and the
 * booking still has no comment. All open bookings of one teacher go into a single message, sent through the same
 * Foodsharing message channel (with Telegram forwarding) as the teacher's booking confirmations.
 */
@Service
public class TeacherCommentReminderService {

    private static final Logger log = LoggerFactory.getLogger(TeacherCommentReminderService.class);
    private static final Set<SlotStatus> COUNTED_STATUSES = Set.of(SlotStatus.BOOKED, SlotStatus.DONE);

    private final SlotRepository slotRepository;
    private final FoodsharingMessageService messageService;
    private final MessageTemplateService messageTemplateService;
    private final FoodsharingAvailability foodsharingAvailability;
    private final AppProperties appProperties;
    private final boolean enabled;
    private final Duration delay;
    private final Duration lookback;

    public TeacherCommentReminderService(SlotRepository slotRepository,
                                         FoodsharingMessageService messageService,
                                         MessageTemplateService messageTemplateService,
                                         FoodsharingAvailability foodsharingAvailability,
                                         AppProperties appProperties,
                                         @Value("${app.comment-reminder.enabled:true}") boolean enabled,
                                         @Value("${app.comment-reminder.delay:PT24H}") Duration delay,
                                         @Value("${app.comment-reminder.lookback:P7D}") Duration lookback) {
        this.slotRepository = slotRepository;
        this.messageService = messageService;
        this.messageTemplateService = messageTemplateService;
        this.foodsharingAvailability = foodsharingAvailability;
        this.appProperties = appProperties;
        this.enabled = enabled;
        this.delay = delay;
        this.lookback = lookback;
    }

    @Scheduled(initialDelayString = "${app.comment-reminder.initial-delay-ms:120000}", fixedDelayString = "${app.comment-reminder.interval-ms:900000}")
    @Transactional
    public void sendDueReminders() {
        if (!enabled || foodsharingAvailability.isDown()) {
            return;
        }
        Instant now = Instant.now();
        Instant startedBefore = now.minus(delay);
        // Only recent EinAbs: older bookings without a comment (e.g. from before this reminder existed) are left alone.
        Instant startedAfter = startedBefore.minus(lookback);
        List<Slot> due = slotRepository.findAllAwaitingCommentReminder(COUNTED_STATUSES, startedAfter, startedBefore);
        if (due.isEmpty()) {
            return;
        }
        // One message per teacher and Bezirk, so the link points to the right teacher page.
        Map<String, List<Slot>> byTeacher = new LinkedHashMap<>();
        for (Slot slot : due) {
            if (!slot.getEinAb().getBezirk().isActive()) {
                // The teacher page of a disabled Bezirk cannot be opened, so the reminder link would lead nowhere.
                continue;
            }
            byTeacher.computeIfAbsent(slot.getTeacher().getId() + "/" + slot.getEinAb().getBezirk().getId(), key -> new java.util.ArrayList<>()).add(slot);
        }
        for (List<Slot> slots : byTeacher.values()) {
            User teacher = slots.get(0).getTeacher();
            // Marked before sending, so a teacher who cannot be reached is not retried every run.
            slots.forEach(slot -> slot.setCommentReminderSentAt(now));
            if (!teacher.isActive() || teacher.getFoodsharingId() == null || teacher.getFoodsharingId().isBlank()) {
                continue;
            }
            LanguageCode language = teacher.getPreferredLanguage() == null ? LanguageCode.DE : teacher.getPreferredLanguage();
            String teacherUrl = appProperties.getFrontend().getBaseUrl()
                    + "/bezirke/" + slots.get(0).getEinAb().getBezirk().getSlug() + "/teacher";
            try {
                messageService.send(
                        teacher.getFoodsharingId(),
                        messageTemplateService.teacherCommentReminderSubject(language),
                        messageTemplateService.teacherCommentReminderBody(language, teacher, slots, teacherUrl));
                log.info("Sent comment reminder to teacher {} for {} booking(s)", teacher.getFoodsharingId(), slots.size());
            } catch (RuntimeException exception) {
                log.warn("Could not send comment reminder to teacher {}: {}", teacher.getFoodsharingId(), exception.getMessage());
            }
        }
    }
}
