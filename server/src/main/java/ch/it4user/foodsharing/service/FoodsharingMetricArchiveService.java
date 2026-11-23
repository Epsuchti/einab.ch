package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingMetricDailyCount;
import ch.it4user.foodsharing.domain.entity.FoodsharingOpenSlotAdvertisementAudit;
import ch.it4user.foodsharing.domain.entity.FoodsharingPickupAutomationAudit;
import ch.it4user.foodsharing.domain.entity.FoodsharingRequestAutomationAudit;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoCancellation;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.FoodsharingPickupAutomationDecision;
import ch.it4user.foodsharing.repository.FoodsharingMetricDailyCountRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns audit rows into anonymous daily metric values ("contributions").
 *
 * <p>Archive methods take a loader so the rows are read inside the archive transaction (lazy associations work).
 *
 * <p>The same contributions are used twice: {@link FoodsharingMetricsService} computes them live for rows that still
 * exist, and the archive methods here add them to {@link FoodsharingMetricDailyCount} right before rows are deleted.
 * Every row is therefore counted exactly once, either live or archived, no matter when the retention cleanup runs.
 */
@Service
public class FoodsharingMetricArchiveService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Zurich");

    /** Advertisement statuses that mean a message was really sent to fill the pickup. */
    public static final Set<String> SENT_ADVERTISEMENT_STATUSES = Set.of(
            "SENT",
            "LATE_CANCELLATION_REMINDER_SENT",
            "LATE_CANCELLATION_ADVERTISEMENT_SENT",
            "LATE_CANCELLATION_REMINDER_AND_ADVERTISEMENT_SENT");
    private static final Set<String> FINAL_CANCELLATION_STATUSES = Set.of("REPLACED", "NOT_REPLACED", "REJOINED");

    /** Rule keys of the slot approval automation, matched against the (German) reason texts of an audit. */
    private static final Map<String, String> RULE_MESSAGE_KEYS = new LinkedHashMap<>();
    static {
        RULE_MESSAGE_KEYS.put("GAP", "message.automation.gap");
        RULE_MESSAGE_KEYS.put("EXPERIENCE", "message.automation.experience");
        RULE_MESSAGE_KEYS.put("CLEANING", "message.automation.cleaning-required");
        RULE_MESSAGE_KEYS.put("NO_CLEANING_STORE", "message.automation.no-cleaning-store");
        RULE_MESSAGE_KEYS.put("HOLIDAY_CALENDAR", "message.automation.public-holiday-calendar-unavailable");
        RULE_MESSAGE_KEYS.put("PUBLIC_HOLIDAY", "message.automation.public-holiday");
        RULE_MESSAGE_KEYS.put("MANUAL_DATE", "message.automation.manual-date-exclusion");
    }

    private final FoodsharingMetricDailyCountRepository dailyCountRepository;
    private final FoodsharingStoreAutomationRepository storeAutomationRepository;
    private final MessageSource messageSource;
    private volatile Map<String, String> rulePrefixes;

    public FoodsharingMetricArchiveService(FoodsharingMetricDailyCountRepository dailyCountRepository,
            FoodsharingStoreAutomationRepository storeAutomationRepository, MessageSource messageSource) {
        this.dailyCountRepository = dailyCountRepository;
        this.storeAutomationRepository = storeAutomationRepository;
        this.messageSource = messageSource;
    }

    /** Receives one contribution: {@code value} is added to {@code key} on {@code day} for the Bezirk. */
    @FunctionalInterface
    public interface Sink {
        void add(UUID bezirkId, LocalDate day, String key, long value);
    }

    /**
     * Which Bezirk a store's metrics count for: the Bezirk of its slot approval automation (unique per store),
     * otherwise the given fallback, i.e. the Bezirk of the account that automates the store.
     */
    public record StoreBezirks(Map<Long, UUID> byStore) {
        public UUID of(long storeId, UUID fallback) {
            return byStore.getOrDefault(storeId, fallback);
        }
    }

    public StoreBezirks storeBezirks() {
        Map<Long, UUID> byStore = new HashMap<>();
        storeAutomationRepository.findAll().forEach(automation -> {
            if (automation.getBezirk() != null) byStore.put(automation.getStoreId(), automation.getBezirk().getId());
        });
        return new StoreBezirks(byStore);
    }

    // ---- Contributions ------------------------------------------------------------------------------------------

    public void contribute(FoodsharingPickupAutomationAudit audit, StoreBezirks stores, Sink sink) {
        if (audit.getBezirk() == null || audit.getDecision() == null) return;
        UUID bezirkId = stores.of(audit.getStoreId(), audit.getBezirk().getId());
        LocalDate day = day(audit.getCreatedAt());
        FoodsharingPickupAutomationDecision decision = audit.getDecision();
        sink.add(bezirkId, day, "SLOT_APPROVAL." + decision.name(), 1);
        String rulePrefix = switch (decision) {
            case DECLINED, WOULD_DECLINE -> "SLOT_DECLINE_RULE.";
            case SKIPPED -> "SLOT_SKIP_RULE.";
            default -> null;
        };
        if (rulePrefix != null) {
            List<String> rules = rules(audit.getReasons());
            if (rules.isEmpty()) {
                sink.add(bezirkId, day, rulePrefix + "OTHER", 1);
            }
            for (String rule : rules) {
                sink.add(bezirkId, day, rulePrefix + rule, 1);
            }
        }
    }

    public void contribute(FoodsharingRequestAutomationAudit audit, StoreBezirks stores, Sink sink) {
        UUID bezirkId = audit.getAutomation() == null ? null : stores.of(audit.getStoreId(), bezirkId(audit.getAutomation().getAdminConnection()));
        if (bezirkId == null || audit.getStatus() == null || audit.getStatus().isBlank()) return;
        sink.add(bezirkId, day(audit.getCreatedAt()), "APPLICATION." + audit.getStatus(), 1);
    }

    public void contribute(FoodsharingOpenSlotAdvertisementAudit audit, StoreBezirks stores, Sink sink, Instant now) {
        UUID bezirkId = audit.getAutomation() == null ? null : stores.of(audit.getStoreId(), bezirkId(audit.getAutomation().getAdminConnection()));
        if (bezirkId == null || audit.isDryRun() || audit.getStatus() == null) return;
        String status = audit.getStatus();
        if ("OCCUPIED_AT_TRIGGER".equals(status) || "LATE_CANCELLATION_NO_ACTION".equals(status)) {
            // The pickup was full when the advertisement became due, so nothing had to be sent.
            sink.add(bezirkId, day(audit.getCreatedAt()), "ADVERT.NOT_NEEDED", 1);
            return;
        }
        if (!SENT_ADVERTISEMENT_STATUSES.contains(status)) return;
        Instant sentAt = audit.getSentAt() != null ? audit.getSentAt() : audit.getCreatedAt();
        sink.add(bezirkId, day(sentAt), "NOTIFICATION." + status, 1);
        if (audit.getFilledAt() != null) {
            LocalDate filledDay = day(audit.getFilledAt());
            sink.add(bezirkId, filledDay, "ADVERT.FILLED", 1);
            sink.add(bezirkId, filledDay, "ADVERT.FILL_MINUTES", Math.max(0, Duration.between(sentAt, audit.getFilledAt()).toMinutes()));
        } else if (audit.getPickupDate() != null && audit.getPickupDate().isBefore(now)) {
            sink.add(bezirkId, day(audit.getPickupDate()), "ADVERT.UNFILLED", 1);
        }
    }

    public void contribute(FoodsharingStoreInfoCancellation cancellation, StoreBezirks stores, Sink sink) {
        UUID bezirkId = stores.of(cancellation.getStoreId(), bezirkId(cancellation.getAdminConnection()));
        if (bezirkId == null || cancellation.getCancelledAt() == null) return;
        LocalDate cancelledDay = day(cancellation.getCancelledAt());
        sink.add(bezirkId, cancelledDay, "LATE_CANCEL.COUNT", 1);
        if (cancellation.getPickupDate() != null) {
            double hours = Duration.between(cancellation.getCancelledAt(), cancellation.getPickupDate()).toMinutes() / 60.0;
            sink.add(bezirkId, cancelledDay, "LATE_CANCEL.HOURS." + hoursBucket(hours), 1);
            if (cancellation.getStatus() != null && FINAL_CANCELLATION_STATUSES.contains(cancellation.getStatus())) {
                sink.add(bezirkId, day(cancellation.getPickupDate()), "LATE_CANCEL." + cancellation.getStatus(), 1);
            }
        }
        if (!cancellation.isDryRun()) {
            if (cancellation.getImmediateNotifiedAt() != null) {
                sink.add(bezirkId, day(cancellation.getImmediateNotifiedAt()), "NOTIFICATION.STORE_INFO_IMMEDIATE", 1);
            }
            if (cancellation.getFinalNotifiedAt() != null) {
                sink.add(bezirkId, day(cancellation.getFinalNotifiedAt()), "NOTIFICATION.STORE_INFO_FINAL", 1);
            }
        }
    }

    // ---- Archiving ----------------------------------------------------------------------------------------------

    @Transactional
    public void archivePickupAudits(Supplier<? extends Collection<FoodsharingPickupAutomationAudit>> loader) {
        Collection<FoodsharingPickupAutomationAudit> audits = loader.get();
        Map<ArchiveKey, Long> totals = new LinkedHashMap<>();
        StoreBezirks stores = storeBezirks();
        audits.forEach(audit -> contribute(audit, stores, collector(totals)));
        save(totals);
    }

    @Transactional
    public void archiveRequestAudits(Supplier<? extends Collection<FoodsharingRequestAutomationAudit>> loader) {
        Collection<FoodsharingRequestAutomationAudit> audits = loader.get();
        Map<ArchiveKey, Long> totals = new LinkedHashMap<>();
        StoreBezirks stores = storeBezirks();
        audits.forEach(audit -> contribute(audit, stores, collector(totals)));
        save(totals);
    }

    @Transactional
    public void archiveAdvertisementAudits(Supplier<? extends Collection<FoodsharingOpenSlotAdvertisementAudit>> loader) {
        Collection<FoodsharingOpenSlotAdvertisementAudit> audits = loader.get();
        Map<ArchiveKey, Long> totals = new LinkedHashMap<>();
        Instant now = Instant.now();
        StoreBezirks stores = storeBezirks();
        audits.forEach(audit -> contribute(audit, stores, collector(totals), now));
        save(totals);
    }

    @Transactional
    public void archiveCancellations(Supplier<? extends Collection<FoodsharingStoreInfoCancellation>> loader) {
        Collection<FoodsharingStoreInfoCancellation> cancellations = loader.get();
        Map<ArchiveKey, Long> totals = new LinkedHashMap<>();
        StoreBezirks stores = storeBezirks();
        cancellations.forEach(cancellation -> contribute(cancellation, stores, collector(totals)));
        save(totals);
    }

    /** Records an event that has no audit row, e.g. an expired booking confirmation. */
    @Transactional
    public void increment(Bezirk bezirk, Instant at, String key, long value) {
        if (bezirk == null || value == 0) return;
        save(Map.of(new ArchiveKey(bezirk.getId(), day(at), key), value));
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    public static LocalDate day(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    static String hoursBucket(double hours) {
        if (hours < 6) return "LT6";
        if (hours < 12) return "LT12";
        if (hours < 24) return "LT24";
        if (hours < 48) return "LT48";
        return "GE48";
    }

    public static UUID bezirkId(FoodsharingAdminConnection connection) {
        if (connection == null) return null;
        User adminUser = connection.getAdminUser();
        return adminUser == null || adminUser.getBezirk() == null ? null : adminUser.getBezirk().getId();
    }

    private List<String> rules(String reasons) {
        if (reasons == null || reasons.isBlank()) return List.of();
        return rulePrefixes().entrySet().stream()
                .filter(entry -> reasons.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Static text of each rule message before its first placeholder, as written by the automation (German). */
    private Map<String, String> rulePrefixes() {
        Map<String, String> prefixes = rulePrefixes;
        if (prefixes == null) {
            prefixes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : RULE_MESSAGE_KEYS.entrySet()) {
                String template = messageSource.getMessage(entry.getValue(), null, "", Locale.GERMAN);
                if (template == null) continue;
                int placeholder = template.indexOf('{');
                String prefix = (placeholder >= 0 ? template.substring(0, placeholder) : template).trim();
                if (prefix.length() >= 10) {
                    prefixes.put(entry.getKey(), prefix);
                }
            }
            rulePrefixes = prefixes;
        }
        return prefixes;
    }

    private static Sink collector(Map<ArchiveKey, Long> totals) {
        return (bezirkId, day, key, value) -> totals.merge(new ArchiveKey(bezirkId, day, key), value, Long::sum);
    }

    private void save(Map<ArchiveKey, Long> totals) {
        totals.forEach((key, value) -> {
            if (value == 0) return;
            FoodsharingMetricDailyCount count = dailyCountRepository
                    .findByBezirkIdAndMetricDayAndMetricKey(key.bezirkId(), key.day(), key.key())
                    .orElseGet(() -> {
                        FoodsharingMetricDailyCount created = new FoodsharingMetricDailyCount();
                        created.setBezirkId(key.bezirkId());
                        created.setMetricDay(key.day());
                        created.setMetricKey(key.key());
                        return created;
                    });
            count.setMetricValue(count.getMetricValue() + value);
            dailyCountRepository.save(count);
        });
    }

    private record ArchiveKey(UUID bezirkId, LocalDate day, String key) {}
}
