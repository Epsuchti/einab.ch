package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoCancellation;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoPickupSnapshot;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoSettings;
import ch.it4user.foodsharing.domain.enumtype.UserPermission;
import ch.it4user.foodsharing.repository.FoodsharingAdminConnectionRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreInfoCancellationRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreInfoPickupSnapshotRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreInfoSettingsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * "Bieb Store Info": tells the store manager over Telegram when a foodsaver signed out of a pickup
 * and nobody took over the free slot.
 *
 * <p>Runs once a day (after {@link #DAILY_CHECK_TIME} Swiss time) to keep the traffic to Foodsharing low.
 * Cancellations are read from the store log of the last {@link #LOG_LOOKBACK} (action type 12 = signed out of
 * a pickup slot). Each check also records the slot count and occupancy of the store's upcoming pickups, because
 * the pickup history has no slot count. Once the pickup time has passed, the people in the history are compared
 * with the slot count recorded on the last check before the pickup. A pickup is never compared with other weeks:
 * a pickup with fewer slots than last week (slot removed, or last week had an extra slot) is not a missing person.
 * Without a recorded slot count no message is sent. Cases recorded by older versions may still carry a slot
 * snapshot, which is preferred.
 */
@Service
public class FoodsharingStoreInfoService {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingStoreInfoService.class);
    static final int SIGN_OUT_SLOT_ACTION = 12;
    private static final ZoneId SWISS_ZONE = ZoneId.of("Europe/Zurich");
    private static final DateTimeFormatter PICKUP_FORMAT = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy HH:mm", Locale.GERMAN).withZone(SWISS_ZONE);
    /** Covers one missed daily run; already recorded sign-outs are de-duplicated. */
    private static final Duration LOG_LOOKBACK = Duration.ofDays(3);
    private static final LocalTime DAILY_CHECK_TIME = LocalTime.of(3, 0);
    private static final Duration SNAPSHOT_MAX_AGE_AT_PICKUP = Duration.ofHours(2);
    private static final Duration RETENTION = Duration.ofDays(180);
    /** Pickup snapshots are only needed until the final check after the pickup (which may be delayed by an outage). */
    private static final Duration PICKUP_SNAPSHOT_RETENTION = Duration.ofDays(30);

    static final String STATUS_OPEN = "OPEN";
    static final String STATUS_NOT_REPLACED = "NOT_REPLACED";
    static final String STATUS_REPLACED = "REPLACED";
    static final String STATUS_REJOINED = "REJOINED";
    static final String STATUS_UNKNOWN = "UNKNOWN";

    private final AppProperties appProperties;
    private final CurrentActorService currentActorService;
    private final CryptoService cryptoService;
    private final FoodsharingPickupApiClient client;
    private final FoodsharingAdminConnectionRepository connectionRepository;
    private final FoodsharingStoreInfoSettingsRepository settingsRepository;
    private final FoodsharingStoreInfoCancellationRepository cancellationRepository;
    private final FoodsharingStoreInfoPickupSnapshotRepository pickupSnapshotRepository;
    private final FoodsharingMetricArchiveService metricArchiveService;
    private final ObjectMapper objectMapper;
    private final FoodsharingAvailability availability;
    private final FoodsharingStoreAccessService storeAccessService;
    private final RestClient telegramClient = RestClient.create("https://api.telegram.org");
    private final OutboundMessageGuard outboundGuard;

    public FoodsharingStoreInfoService(AppProperties appProperties, CurrentActorService currentActorService, CryptoService cryptoService,
                                       FoodsharingPickupApiClient client, FoodsharingAdminConnectionRepository connectionRepository,
                                       FoodsharingStoreInfoSettingsRepository settingsRepository,
                                       FoodsharingStoreInfoCancellationRepository cancellationRepository, ObjectMapper objectMapper,
                                       FoodsharingMetricArchiveService metricArchiveService, FoodsharingAvailability availability,
                                       FoodsharingStoreAccessService storeAccessService,
                                       FoodsharingStoreInfoPickupSnapshotRepository pickupSnapshotRepository,
                                       OutboundMessageGuard outboundGuard) {
        this.outboundGuard = outboundGuard;
        this.pickupSnapshotRepository = pickupSnapshotRepository;
        this.availability = availability;
        this.storeAccessService = storeAccessService;
        this.metricArchiveService = metricArchiveService;
        this.appProperties = appProperties;
        this.currentActorService = currentActorService;
        this.cryptoService = cryptoService;
        this.client = client;
        this.connectionRepository = connectionRepository;
        this.settingsRepository = settingsRepository;
        this.cancellationRepository = cancellationRepository;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- settings

    @Transactional
    public SettingsView settings() {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingAdminConnection connection = requireConnection();
        FoodsharingStoreInfoSettings settings = settingsRepository.findByAdminConnection(connection).orElseGet(FoodsharingStoreInfoSettings::new);
        return view(settings, connection, managedStores(connection));
    }

    @Transactional
    public SettingsView save(SettingsRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingAdminConnection connection = requireConnection();
        List<String> chatIds = normalizeChatIds(request.telegramChatIds());
        List<StoreRef> stores = request.stores() == null ? List.of() : request.stores().stream()
                .filter(Objects::nonNull)
                .filter(store -> store.storeId() > 0)
                .collect(java.util.stream.Collectors.toMap(StoreRef::storeId, store -> store, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
        List<String> errors = new ArrayList<>();
        if (request.cutoffEnabled() && request.maximumHoursBeforePickup() < 1) {
            errors.add("The cancellation window must be at least 1 hour.");
        }
        if (request.enabled()) {
            if (chatIds.isEmpty()) errors.add("Select at least one Telegram recipient.");
            if (stores.isEmpty()) errors.add("Select at least one store.");
            if (telegramToken(connection) == null) errors.add("Telegram bot token is not configured.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, errors);
        }
        FoodsharingStoreInfoSettings settings = settingsRepository.findByAdminConnection(connection).orElseGet(() -> {
            FoodsharingStoreInfoSettings created = new FoodsharingStoreInfoSettings();
            created.setAdminConnection(connection);
            return created;
        });
        if (request.enabled() && (!settings.isEnabled() || settings.getActiveSince() == null)) {
            settings.setActiveSince(Instant.now());
        }
        settings.setEnabled(request.enabled());
        settings.setCutoffEnabled(request.cutoffEnabled());
        settings.setMaximumHoursBeforePickup(Math.max(1, request.maximumHoursBeforePickup()));
        settings.setTelegramChatIdsJson(write(chatIds));
        settings.setStoresJson(write(stores));
        settingsRepository.save(settings);
        return view(settings, connection, managedStores(connection));
    }

    public void sendTestMessage(List<String> telegramChatIds) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingAdminConnection connection = requireConnection();
        String token = telegramToken(connection);
        if (token == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Telegram bot token is not configured."));
        }
        List<String> chatIds = normalizeChatIds(telegramChatIds);
        if (chatIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Select at least one Telegram recipient."));
        }
        // Example: yesterday evening's pickup, as it would be reported by the daily check this morning.
        Instant pickup = LocalDate.now(SWISS_ZONE).minusDays(1).atTime(18, 0).atZone(SWISS_ZONE).toInstant();
        String text = "✅ Testnachricht von Bieb Store Info\n"
                + "Dieser Chat ist eingerichtet. Einmal täglich am Morgen bekommst du hier eine Nachricht, "
                + "wenn sich jemand kurzfristig von einer Abholung ausgetragen hat und der Slot leer geblieben ist.\n\n"
                + "So sieht eine Meldung aus:\n\n"
                + finalMessage("Beispiel-Betrieb", 0, pickup, "Max Muster", "123456", pickup.minus(Duration.ofHours(18)), 1, 3, false);
        List<String> failures = new ArrayList<>();
        for (String chatId : chatIds) {
            try {
                sendTelegram(token, chatId, text);
            } catch (RuntimeException ex) {
                failures.add(chatId + ": " + ex.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Telegram test message failed for " + String.join(", ", failures)));
        }
    }

    @Transactional(readOnly = true)
    public List<CancellationView> recentCancellations() {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingAdminConnection connection = requireConnection();
        return cancellationRepository.findTop100ByAdminConnectionOrderByPickupDateDesc(connection).stream()
                .map(c -> new CancellationView(c.getStoreId(), c.getStoreName(), c.getPickupDate(), c.getFoodsharingUserId(),
                        c.getFoodsharingUserName(), c.getCancelledAt(), hoursBetween(c.getCancelledAt(), c.getPickupDate()),
                        c.getTotalSlots(), c.getOccupiedSlots(), c.getStatus(), c.getImmediateNotifiedAt(), c.getFinalNotifiedAt(),
                        c.isDryRun(), c.getError()))
                .toList();
    }

    @Transactional
    public void deleteForConnection(FoodsharingAdminConnection connection) {
        metricArchiveService.archiveCancellations(() -> cancellationRepository.findAllByAdminConnection(connection));
        cancellationRepository.deleteAllByAdminConnection(connection);
        pickupSnapshotRepository.deleteAllByAdminConnection(connection);
        settingsRepository.deleteAllByAdminConnection(connection);
    }

    // ---------------------------------------------------------------- scheduled run

    /** Called from the scheduled automation run (inside its transaction). */
    public void runScheduled() {
        if (!appProperties.getFoodsharing().getAutomation().isEnabled()) {
            return;
        }
        boolean dryRun = appProperties.getFoodsharing().getAutomation().isDryRun();
        // Whole seconds: the Foodsharing client formats path timestamps with millisecond precision.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant dueSince = now.atZone(SWISS_ZONE).toLocalDate().atTime(DAILY_CHECK_TIME).atZone(SWISS_ZONE).toInstant();
        if (now.isBefore(dueSince)) {
            return;
        }
        List<FoodsharingStoreInfoSettings> due = settingsRepository.findAllByEnabledTrue().stream()
                .filter(settings -> settings.getLastCheckedAt() == null || settings.getLastCheckedAt().isBefore(dueSince))
                .toList();
        if (due.isEmpty()) {
            return;
        }
        metricArchiveService.archiveCancellations(() -> cancellationRepository.findAllByPickupDateBefore(now.minus(RETENTION)));
        cancellationRepository.deleteAllByPickupDateBefore(now.minus(RETENTION));
        pickupSnapshotRepository.deleteAllByPickupDateBefore(now.minus(PICKUP_SNAPSHOT_RETENTION));
        for (FoodsharingStoreInfoSettings settings : due) {
            for (StoreRef store : read(settings.getStoresJson(), new TypeReference<List<StoreRef>>() {})) {
                try {
                    processStore(settings, store, now, dryRun);
                } catch (RuntimeException ex) {
                    if (availability.reportFailure("storeInfo", ex)) {
                        // Not marked as checked: the whole daily check runs again once Foodsharing is back.
                        return;
                    }
                    log.warn("Bieb store info failed for store {} ({}): {}", store.storeId(), store.storeName(), ex.getMessage(), ex);
                }
            }
            // Marked as checked even after a store failed: its open cases are picked up again tomorrow and the
            // log lookback covers the missed day, so a broken store does not cause a retry every few minutes.
            // By id, not via the entity: an admin may have saved the settings during the check (version check would fail).
            settingsRepository.markChecked(settings.getId(), now);
        }
    }

    private void processStore(FoodsharingStoreInfoSettings settings, StoreRef store, Instant now, boolean dryRun) {
        FoodsharingAdminConnection connection = settings.getAdminConnection();
        Instant activeSince = settings.getActiveSince() == null ? settings.getCreatedAt() : settings.getActiveSince();
        String token = telegramToken(connection);
        List<String> chatIds = read(settings.getTelegramChatIdsJson(), new TypeReference<List<String>>() {});

        // 1. Record new sign-outs. If someone signs out several times for the same pickup, the latest sign-out counts.
        Map<String, FoodsharingPickupModels.StoreLogEntry> latestSignOuts = new LinkedHashMap<>();
        for (FoodsharingPickupModels.StoreLogEntry entry : client.storeLog(connection, store.storeId(), now.minus(LOG_LOOKBACK), now.plus(Duration.ofHours(1)), SIGN_OUT_SLOT_ACTION)) {
            if (entry.actionType() != SIGN_OUT_SLOT_ACTION || entry.actorId() == null || entry.actorId().isBlank()) continue;
            latestSignOuts.merge(entry.actorId() + "|" + entry.dateReference(), entry,
                    (a, b) -> a.performedAt().isAfter(b.performedAt()) ? a : b);
        }
        for (FoodsharingPickupModels.StoreLogEntry entry : latestSignOuts.values()) {
            if (entry.dateReference().isBefore(activeSince)) continue;
            if (settings.isCutoffEnabled()
                    && Duration.between(entry.performedAt(), entry.dateReference()).compareTo(Duration.ofHours(settings.getMaximumHoursBeforePickup())) > 0) {
                continue;
            }
            Optional<FoodsharingStoreInfoCancellation> existing = cancellationRepository
                    .findByAdminConnectionAndStoreIdAndPickupDateAndFoodsharingUserId(connection, store.storeId(), entry.dateReference(), entry.actorId());
            if (existing.isPresent()) {
                FoodsharingStoreInfoCancellation cancellation = existing.get();
                if (STATUS_OPEN.equals(cancellation.getStatus()) && entry.performedAt().isAfter(cancellation.getCancelledAt())) {
                    cancellation.setCancelledAt(entry.performedAt());
                    cancellationRepository.save(cancellation);
                }
                continue;
            }
            FoodsharingStoreInfoCancellation cancellation = new FoodsharingStoreInfoCancellation();
            cancellation.setAdminConnection(connection);
            cancellation.setStoreId(store.storeId());
            cancellation.setStoreName(store.storeName() == null ? "" : store.storeName());
            cancellation.setPickupDate(entry.dateReference());
            cancellation.setFoodsharingUserId(entry.actorId());
            cancellation.setFoodsharingUserName(entry.actorName());
            cancellation.setCancelledAt(entry.performedAt());
            cancellationRepository.save(cancellation);
        }

        // 2. Record slot count and occupancy of the upcoming pickups. A sign-out that happens after this check is
        // judged against the slot count seen here at the next check.
        recordPickupSnapshots(connection, store.storeId(), now);

        List<FoodsharingStoreInfoCancellation> open = cancellationRepository.findAllByAdminConnectionAndStatus(connection, STATUS_OPEN).stream()
                .filter(c -> c.getStoreId() == store.storeId())
                .sorted(Comparator.comparing(FoodsharingStoreInfoCancellation::getPickupDate))
                .toList();
        if (open.isEmpty()) return;

        // 3. Pickups that took place: was the slot still empty at pickup time?
        List<FoodsharingStoreInfoCancellation> due = open.stream().filter(c -> !c.getPickupDate().isAfter(now)).toList();
        if (due.isEmpty()) return;
        HistoryLoader history = new HistoryLoader(connection, store.storeId(), due);
        for (FoodsharingStoreInfoCancellation c : due) {
            Outcome outcome = finalOutcome(c, history);
            c.setStatus(outcome.status());
            c.setFinalCheckedAt(now);
            if (outcome.totalSlots() != null) c.setTotalSlots(outcome.totalSlots());
            if (outcome.occupiedSlots() != null) c.setOccupiedSlots(outcome.occupiedSlots());
            if (STATUS_NOT_REPLACED.equals(outcome.status())) {
                String text = finalMessage(c.getStoreName(), c.getStoreId(), c.getPickupDate(), c.getFoodsharingUserName(), c.getFoodsharingUserId(), c.getCancelledAt(),
                        outcome.freeSlots(), outcome.totalSlots(), outcome.estimated());
                notify(c, token, chatIds, text, dryRun);
                c.setFinalNotifiedAt(now);
            }
            cancellationRepository.save(c);
        }
    }

    private void recordPickupSnapshots(FoodsharingAdminConnection connection, long storeId, Instant now) {
        for (FoodsharingPickupModels.Pickup pickup : client.pickups(connection, storeId)) {
            if (pickup.date() == null || !pickup.date().isAfter(now)) continue;
            FoodsharingStoreInfoPickupSnapshot snapshot = pickupSnapshotRepository
                    .findByAdminConnectionAndStoreIdAndPickupDate(connection, storeId, pickup.date())
                    .orElseGet(() -> {
                        FoodsharingStoreInfoPickupSnapshot created = new FoodsharingStoreInfoPickupSnapshot();
                        created.setAdminConnection(connection);
                        created.setStoreId(storeId);
                        created.setPickupDate(pickup.date());
                        return created;
                    });
            snapshot.setTotalSlots(Math.max(0, pickup.totalSlots()));
            snapshot.setOccupiedSlots(pickup.users().size());
            snapshot.setCheckedAt(now);
            pickupSnapshotRepository.save(snapshot);
        }
    }

    /** Loads the pickup history of a store once, covering all due cancellations. */
    private final class HistoryLoader {
        private final FoodsharingAdminConnection connection;
        private final long storeId;
        private final Instant from;
        private final Instant to;
        private final List<FoodsharingStoreInfoCancellation> due;
        private List<FoodsharingPickupModels.Pickup> pickups;

        private HistoryLoader(FoodsharingAdminConnection connection, long storeId, List<FoodsharingStoreInfoCancellation> due) {
            this.connection = connection;
            this.storeId = storeId;
            this.due = due;
            this.from = due.stream().map(FoodsharingStoreInfoCancellation::getPickupDate).min(Comparator.naturalOrder()).orElseThrow().minus(Duration.ofHours(1));
            this.to = due.stream().map(FoodsharingStoreInfoCancellation::getPickupDate).max(Comparator.naturalOrder()).orElseThrow().plus(Duration.ofHours(1));
        }

        private List<FoodsharingPickupModels.Pickup> pickups() {
            if (pickups == null) {
                pickups = client.pickupHistory(connection, storeId, from, to);
            }
            return pickups;
        }
    }

    private Outcome finalOutcome(FoodsharingStoreInfoCancellation c, HistoryLoader historyLoader) {
        boolean snapshotUsable = c.getSnapshotAt() != null
                && !c.getSnapshotAt().isBefore(c.getCancelledAt())
                && !c.getSnapshotAt().isBefore(c.getPickupDate().minus(SNAPSHOT_MAX_AGE_AT_PICKUP))
                && c.getTotalSlots() != null && c.getOccupiedSlots() != null;
        if (snapshotUsable) {
            if (c.isCancellerRejoined()) return new Outcome(STATUS_REJOINED, 0, c.getTotalSlots(), c.getOccupiedSlots(), false);
            int free = freeSlots(c.getTotalSlots(), c.getOccupiedSlots());
            return new Outcome(free > 0 ? STATUS_NOT_REPLACED : STATUS_REPLACED, free, c.getTotalSlots(), c.getOccupiedSlots(), false);
        }
        // Who was at the pickup comes from the history; the slot count from the last check before the pickup.
        List<FoodsharingPickupModels.Pickup> history = historyLoader.pickups();
        Optional<FoodsharingPickupModels.Pickup> pickup = history.stream().filter(p -> p.date().equals(c.getPickupDate())).findFirst();
        int occupied = pickup.map(p -> p.users().size()).orElse(0);
        if (pickup.isPresent() && pickup.get().users().stream().anyMatch(u -> c.getFoodsharingUserId().equals(u.id()))) {
            return new Outcome(STATUS_REJOINED, 0, null, occupied, true);
        }
        // Someone who signed up after the sign-out took over the slot.
        if (pickup.isPresent() && hasLaterSignUp(c, pickup.get(), historyLoader.due)) {
            return new Outcome(STATUS_REPLACED, 0, null, occupied, true);
        }
        Optional<FoodsharingStoreInfoPickupSnapshot> snapshot = pickupSnapshotRepository
                .findByAdminConnectionAndStoreIdAndPickupDate(c.getAdminConnection(), c.getStoreId(), c.getPickupDate())
                .filter(s -> s.getCheckedAt().isBefore(c.getPickupDate()));
        // Seen after the sign-out with every slot taken: the freed slot was taken over or removed, nothing stayed empty.
        if (snapshot.isPresent() && !snapshot.get().getCheckedAt().isBefore(c.getCancelledAt())
                && snapshot.get().getOccupiedSlots() >= snapshot.get().getTotalSlots()) {
            return new Outcome(STATUS_REPLACED, 0, snapshot.get().getTotalSlots(), occupied, true);
        }
        Integer total = pickup.filter(p -> p.totalSlots() > 0).map(FoodsharingPickupModels.Pickup::totalSlots)
                .or(() -> snapshot.map(FoodsharingStoreInfoPickupSnapshot::getTotalSlots))
                .or(() -> Optional.ofNullable(c.getTotalSlots()))
                .filter(t -> t > 0)
                .orElse(null);
        if (total == null) {
            // No recorded slot count: we cannot tell whether a slot stayed empty, so nothing is reported.
            return new Outcome(STATUS_UNKNOWN, 0, null, occupied, true);
        }
        int free = Math.max(0, total - occupied);
        return new Outcome(free > 0 ? STATUS_NOT_REPLACED : STATUS_REPLACED, free, total, occupied, true);
    }

    /**
     * True when a foodsaver signed up for the pickup after this sign-out. With several sign-outs from the same pickup,
     * each later sign-up replaces at most one of them, matched in the order of the sign-outs.
     */
    private static boolean hasLaterSignUp(FoodsharingStoreInfoCancellation c, FoodsharingPickupModels.Pickup pickup, List<FoodsharingStoreInfoCancellation> due) {
        List<Instant> signUps = new ArrayList<>(pickup.users().stream()
                .map(FoodsharingPickupModels.PickupUser::signUpDate)
                .filter(Objects::nonNull)
                .sorted()
                .toList());
        List<FoodsharingStoreInfoCancellation> samePickup = due.stream()
                .filter(other -> other.getPickupDate().equals(c.getPickupDate()))
                .sorted(Comparator.comparing(FoodsharingStoreInfoCancellation::getCancelledAt))
                .toList();
        for (FoodsharingStoreInfoCancellation cancellation : samePickup) {
            Optional<Instant> replacement = signUps.stream().filter(signUp -> signUp.isAfter(cancellation.getCancelledAt())).findFirst();
            if (cancellation == c) return replacement.isPresent();
            replacement.ifPresent(signUps::remove);
        }
        return false;
    }

    private void notify(FoodsharingStoreInfoCancellation c, String token, List<String> chatIds, String text, boolean dryRun) {
        c.setMessage(text);
        c.setDryRun(dryRun);
        if (dryRun) {
            log.info("Bieb store info (dry run) would send to {}: {}", chatIds, text);
            return;
        }
        if (token == null) {
            c.setError("Telegram bot token is not configured.");
            return;
        }
        List<String> failures = new ArrayList<>();
        for (String chatId : chatIds) {
            try {
                sendTelegram(token, chatId, text);
            } catch (RuntimeException ex) {
                log.warn("Bieb store info Telegram message to {} failed: {}", chatId, ex.getMessage());
                failures.add(chatId + ": " + ex.getMessage());
            }
        }
        c.setError(failures.isEmpty() ? null : String.join("\n", failures));
    }

    // ---------------------------------------------------------------- messages

    private String finalMessage(String storeName, long storeId, Instant pickupDate, String userName, String userId, Instant cancelledAt, int free, Integer total, boolean estimated) {
        return "❌ Kein Ersatz gefunden\n"
                + "Betrieb: " + storeName + "\n"
                + "Abholung: " + PICKUP_FORMAT.format(pickupDate) + "\n"
                + displayName(userName) + foodsharingIdSuffix(userId) + " hat sich " + durationText(cancelledAt, pickupDate) + " vor der Abholung ausgetragen, der Slot blieb leer.\n"
                + "Freie Slots bei der Abholung: " + free + (total != null && total > 0 ? " von " + total : "")
                + (estimated ? "\n(geschätzt aus dem Abholverlauf)" : "")
                + storeLink(storeId);
    }

    private static String foodsharingIdSuffix(String userId) {
        return userId == null || userId.isBlank() ? "" : " (Foodsharing-ID " + userId.trim() + ")";
    }

    private String storeLink(long storeId) {
        if (storeId <= 0) return "";
        String base = appProperties.getFoodsharing().getBaseUrl();
        return "\n" + (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/store/" + storeId;
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "Jemand" : name;
    }

    private static String durationText(Instant from, Instant to) {
        long minutes = Math.max(0, Duration.between(from, to).toMinutes());
        if (minutes < 60) return minutes + " Min.";
        long hours = minutes / 60;
        if (hours < 48) return hours + " Std.";
        long days = hours / 24;
        long rest = hours % 24;
        return days + " Tage" + (rest > 0 ? " " + rest + " Std." : "");
    }

    // ---------------------------------------------------------------- helpers

    private static int freeSlots(int totalSlots, int occupied) {
        if (totalSlots <= 0) return occupied == 0 ? 1 : 0;
        return Math.max(0, totalSlots - occupied);
    }

    private static double hoursBetween(Instant from, Instant to) {
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
    }

    private void sendTelegram(String token, String chatId, String text) {
        if (!outboundGuard.allowTelegramChat(chatId, text)) return;
        telegramClient.post().uri("/bot{token}/sendMessage", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true))
                .retrieve().toBodilessEntity();
    }

    private String telegramToken(FoodsharingAdminConnection connection) {
        String ciphertext = connection.getTelegramBotTokenCiphertext();
        if (ciphertext == null || ciphertext.isBlank()) return null;
        String token = cryptoService.decrypt(ciphertext);
        return token == null || token.isBlank() ? null : token;
    }

    private List<StoreRef> managedStores(FoodsharingAdminConnection connection) {
        try {
            return storeAccessService.accessibleStores(connection).stream()
                    .map(store -> new StoreRef(store.id(), store.name()))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Could not load managed stores for Bieb store info: {}", ex.getMessage());
            return List.of();
        }
    }

    private SettingsView view(FoodsharingStoreInfoSettings settings, FoodsharingAdminConnection connection, List<StoreRef> availableStores) {
        return new SettingsView(settings.isEnabled(),
                settings.isCutoffEnabled(), settings.getMaximumHoursBeforePickup(),
                read(settings.getTelegramChatIdsJson(), new TypeReference<List<String>>() {}),
                read(settings.getStoresJson(), new TypeReference<List<StoreRef>>() {}),
                availableStores, telegramToken(connection) != null, settings.getActiveSince());
    }

    private static List<String> normalizeChatIds(List<String> chatIds) {
        if (chatIds == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String chatId : chatIds) {
            if (chatId != null && !chatId.isBlank()) result.add(chatId.trim());
        }
        return List.copyOf(result);
    }

    private FoodsharingAdminConnection requireConnection() {
        return connectionRepository.findByAdminUser(currentActorService.requireAutomationUser())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Connect Foodsharing first.")));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not store Bieb store info settings."));
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        try {
            List<T> value = objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, type);
            return value == null ? List.of() : value;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private record Outcome(String status, int freeSlots, Integer totalSlots, Integer occupiedSlots, boolean estimated) {}

    public record StoreRef(long storeId, String storeName) {}
    public record SettingsRequest(boolean enabled, boolean cutoffEnabled,
                                  int maximumHoursBeforePickup, List<String> telegramChatIds, List<StoreRef> stores) {}
    public record SettingsView(boolean enabled, boolean cutoffEnabled,
                               int maximumHoursBeforePickup, List<String> telegramChatIds, List<StoreRef> stores,
                               List<StoreRef> availableStores, boolean telegramBotTokenConfigured, Instant activeSince) {}
    public record CancellationView(long storeId, String storeName, Instant pickupDate, String foodsharingUserId, String foodsharingUserName,
                                   Instant cancelledAt, double hoursBeforePickup, Integer totalSlots, Integer occupiedSlots, String status,
                                   Instant immediateNotifiedAt, Instant finalNotifiedAt, boolean dryRun, String error) {}
}
