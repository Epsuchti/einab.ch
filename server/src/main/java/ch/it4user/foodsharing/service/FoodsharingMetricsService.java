package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingMetricDailyCount;
import ch.it4user.foodsharing.domain.entity.FoodsharingOpenSlotAdvertisementAudit;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.FoodsharingMetricDailyCountRepository;
import ch.it4user.foodsharing.repository.FoodsharingOpenSlotAdvertisementAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingPickupAutomationAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingRequestAutomationAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreInfoCancellationRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metrics for the Metrics tab of the automation page.
 *
 * <p>Audit-based numbers combine the archived daily totals with the audits that still exist (see
 * {@link FoodsharingMetricArchiveService}). EinAb slots and extra slots are permanent data and are counted directly.
 */
@Service
public class FoodsharingMetricsService {
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 366;
    private static final Duration COVERAGE_WINDOW = Duration.ofDays(7);
    private static final Duration UPCOMING_EINAB_WINDOW = Duration.ofDays(30);

    private final CurrentActorService currentActorService;
    private final BezirkService bezirkService;
    private final FoodsharingMetricArchiveService archiveService;
    private final FoodsharingMetricDailyCountRepository dailyCountRepository;
    private final FoodsharingPickupAutomationAuditRepository pickupAuditRepository;
    private final FoodsharingRequestAutomationAuditRepository requestAuditRepository;
    private final FoodsharingOpenSlotAdvertisementAuditRepository advertisementAuditRepository;
    private final FoodsharingStoreInfoCancellationRepository cancellationRepository;
    private final SlotRepository slotRepository;
    private final FoodsharingPickupAutomationService automationService;
    private final FoodsharingMyPickupsService myPickupsService;

    public FoodsharingMetricsService(CurrentActorService currentActorService, BezirkService bezirkService,
            FoodsharingMetricArchiveService archiveService, FoodsharingMetricDailyCountRepository dailyCountRepository,
            FoodsharingPickupAutomationAuditRepository pickupAuditRepository,
            FoodsharingRequestAutomationAuditRepository requestAuditRepository,
            FoodsharingOpenSlotAdvertisementAuditRepository advertisementAuditRepository,
            FoodsharingStoreInfoCancellationRepository cancellationRepository, SlotRepository slotRepository,
            FoodsharingPickupAutomationService automationService, FoodsharingMyPickupsService myPickupsService) {
        this.currentActorService = currentActorService;
        this.bezirkService = bezirkService;
        this.archiveService = archiveService;
        this.dailyCountRepository = dailyCountRepository;
        this.pickupAuditRepository = pickupAuditRepository;
        this.requestAuditRepository = requestAuditRepository;
        this.advertisementAuditRepository = advertisementAuditRepository;
        this.cancellationRepository = cancellationRepository;
        this.slotRepository = slotRepository;
        this.automationService = automationService;
        this.myPickupsService = myPickupsService;
    }

    @Transactional(readOnly = true)
    public MetricsView metrics(String bezirkSlug, Integer requestedDays) {
        User user = currentActorService.requireAutomationUser();
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        if (!user.isCanManageUsers() && (user.getBezirk() == null || !user.getBezirk().getId().equals(bezirk.getId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND);
        }
        int days = requestedDays == null ? DEFAULT_DAYS : Math.max(1, Math.min(MAX_DAYS, requestedDays));
        Instant now = Instant.now();
        LocalDate toDay = FoodsharingMetricArchiveService.day(now);
        LocalDate fromDay = toDay.minusDays(days - 1L);
        Instant periodStart = fromDay.atStartOfDay(FoodsharingMetricArchiveService.ZONE).toInstant();
        UUID bezirkId = bezirk.getId();

        Map<LocalDate, Map<String, Long>> byDay = new TreeMap<>();
        FoodsharingMetricArchiveService.Sink sink = (rowBezirkId, day, key, value) -> {
            if (bezirkId.equals(rowBezirkId) && !day.isBefore(fromDay) && !day.isAfter(toDay)) {
                byDay.computeIfAbsent(day, ignored -> new TreeMap<>()).merge(key, value, Long::sum);
            }
        };
        for (FoodsharingMetricDailyCount row : dailyCountRepository.findAllByBezirkIdAndMetricDayBetween(bezirkId, fromDay, toDay)) {
            sink.add(row.getBezirkId(), row.getMetricDay(), row.getMetricKey(), row.getMetricValue());
        }
        // Every audit counts for the Bezirk its store belongs to, so stores of other Bezirke stay out.
        FoodsharingMetricArchiveService.StoreBezirks stores = archiveService.storeBezirks();
        pickupAuditRepository.findAll().forEach(audit -> archiveService.contribute(audit, stores, sink));
        requestAuditRepository.findAll().forEach(audit -> archiveService.contribute(audit, stores, sink));
        int advertsWaiting = 0;
        for (FoodsharingOpenSlotAdvertisementAudit audit : advertisementAuditRepository.findAll()) {
            archiveService.contribute(audit, stores, sink, now);
            if (!audit.isDryRun() && audit.getFilledAt() == null && audit.getPickupDate() != null && audit.getPickupDate().isAfter(now)
                    && FoodsharingMetricArchiveService.SENT_ADVERTISEMENT_STATUSES.contains(audit.getStatus())
                    && audit.getAutomation() != null
                    && bezirkId.equals(stores.of(audit.getStoreId(),
                            FoodsharingMetricArchiveService.bezirkId(audit.getAutomation().getAdminConnection())))) {
                advertsWaiting++;
            }
        }
        cancellationRepository.findAll().forEach(cancellation -> archiveService.contribute(cancellation, stores, sink));

        // EinAb slots of EinAbs that took place in the period, counted on the EinAb day.
        for (Slot slot : slotRepository.findAllByEinAbBezirkAndEinAbStartDateTimeBetween(bezirk, periodStart, now)) {
            LocalDate day = FoodsharingMetricArchiveService.day(slot.getEinAb().getStartDateTime());
            for (String key : einAbKeys(slot.getStatus())) {
                sink.add(bezirkId, day, key, 1);
            }
        }

        Map<String, Long> totals = new TreeMap<>();
        byDay.values().forEach(values -> values.forEach((key, value) -> totals.merge(key, value, Long::sum)));

        // Upcoming EinAb slots, not tied to the period.
        for (Slot slot : slotRepository.findAllByEinAbBezirkAndEinAbStartDateTimeBetween(bezirk, now, now.plus(UPCOMING_EINAB_WINDOW))) {
            if (slot.getStatus() == SlotStatus.CANCELLED) continue;
            totals.merge("EINAB.UPCOMING_SLOTS", 1L, Long::sum);
            if (slot.getStatus() == SlotStatus.BOOKED) {
                totals.merge("EINAB.UPCOMING_BOOKED", 1L, Long::sum);
            }
        }

        // Extra slots added on the pickups page in the period, for known stores of this Bezirk.
        Set<String> extraSlotUsers = new HashSet<>();
        for (FoodsharingMyPickupsService.ExtraSlotAuditView extraSlot : myPickupsService.extraSlotAudit(bezirkSlug)) {
            LocalDate day = FoodsharingMetricArchiveService.day(extraSlot.createdAt());
            if (day.isBefore(fromDay) || day.isAfter(toDay)) continue;
            if (!hasStoreName(extraSlot.storeId(), extraSlot.storeName()) || !bezirkId.equals(stores.of(extraSlot.storeId(), bezirkId))) continue;
            totals.merge("EXTRA_SLOT.ADDED", 1L, Long::sum);
            if (Boolean.TRUE.equals(extraSlot.filled())) totals.merge("EXTRA_SLOT.FILLED", 1L, Long::sum);
            extraSlotUsers.add(extraSlot.foodsharingUserId());
        }
        if (!extraSlotUsers.isEmpty()) totals.put("EXTRA_SLOT.USERS", (long) extraSlotUsers.size());

        List<MetricDailyValue> daily = new ArrayList<>();
        byDay.forEach((day, values) -> values.forEach((key, value) -> {
            if (key.startsWith("NOTIFICATION.") || key.equals("ADVERT.FILLED") || key.equals("LATE_CANCEL.COUNT")) {
                daily.add(new MetricDailyValue(day, key, value));
            }
        }));

        return new MetricsView(days, fromDay, toDay, totals, daily, advertsWaiting, coverage(bezirk, stores, now));
    }

    /** False for stores the pickups page only knows by id ("#123"), e.g. stores without any automation. */
    static boolean hasStoreName(long storeId, String storeName) {
        return storeName != null && !storeName.isBlank() && !storeName.equals("#" + storeId);
    }

    private static List<String> einAbKeys(SlotStatus status) {
        return switch (status) {
            case CANCELLED -> List.of("EINAB.CANCELLED");
            case DONE -> List.of("EINAB.SLOTS", "EINAB.BOOKED", "EINAB.DONE");
            case BOOKED -> List.of("EINAB.SLOTS", "EINAB.BOOKED");
            case AVAILABLE -> List.of("EINAB.SLOTS", "EINAB.UNBOOKED");
        };
    }

    private CoverageView coverage(Bezirk bezirk, FoodsharingMetricArchiveService.StoreBezirks storeBezirks, Instant now) {
        Instant to = now.plus(COVERAGE_WINDOW);
        List<FoodsharingPickupAutomationService.StoreUpcomingPickupsView> stores = new ArrayList<>(automationService.upcomingStorePickups(bezirk, now, to));
        // Only named stores of this Bezirk: unnamed ones have no automation and may belong to another Bezirk.
        stores.removeIf(store -> !hasStoreName(store.storeId(), store.storeName())
                || !bezirk.getId().equals(storeBezirks.of(store.storeId(), bezirk.getId())));
        stores.sort(Comparator.comparingInt((FoodsharingPickupAutomationService.StoreUpcomingPickupsView store) -> store.occupiedSlots() - store.totalSlots())
                .thenComparing(FoodsharingPickupAutomationService.StoreUpcomingPickupsView::storeName, String.CASE_INSENSITIVE_ORDER));
        int pickups = 0;
        int pickupsWithOpenSlots = 0;
        int totalSlots = 0;
        int occupiedSlots = 0;
        Instant oldestRefreshAt = null;
        for (var store : stores) {
            pickups += store.pickups();
            pickupsWithOpenSlots += store.pickupsWithOpenSlots();
            totalSlots += store.totalSlots();
            occupiedSlots += store.occupiedSlots();
            if (store.refreshedAt() != null && (oldestRefreshAt == null || store.refreshedAt().isBefore(oldestRefreshAt))) {
                oldestRefreshAt = store.refreshedAt();
            }
        }
        return new CoverageView(now, to, pickups, pickupsWithOpenSlots, totalSlots, occupiedSlots, oldestRefreshAt, stores);
    }

    public record MetricsView(int periodDays, LocalDate fromDay, LocalDate toDay, Map<String, Long> totals,
            List<MetricDailyValue> daily, int advertsWaiting, CoverageView coverage) {}

    public record MetricDailyValue(LocalDate day, String key, long value) {}

    public record CoverageView(Instant from, Instant to, int pickups, int pickupsWithOpenSlots, int totalSlots, int occupiedSlots,
            Instant oldestRefreshAt, List<FoodsharingPickupAutomationService.StoreUpcomingPickupsView> stores) {}
}
