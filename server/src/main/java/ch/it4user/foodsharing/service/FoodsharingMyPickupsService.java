package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingMyPickupsStoreCache;
import ch.it4user.foodsharing.domain.entity.FoodsharingPickupExtraSlot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.repository.FoodsharingMyPickupsStoreCacheRepository;
import ch.it4user.foodsharing.repository.FoodsharingPickupExtraSlotRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Shows the current user's Foodsharing pickups and lets the first person of a pickup add one extra slot.
 *
 * <p>Foodsharing is accessed with the Bezirk admin account, or the fallback account from the application
 * properties when the Bezirk has none ({@link FoodsharingClient#accountKey}). Automation connections are never used.
 * Only stores this account is responsible for are shown. Store pickups are cached per account and reloaded from
 * Foodsharing at most once every {@link #REFRESH_AFTER}, however often the page is opened.
 */
@Service
public class FoodsharingMyPickupsService {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingMyPickupsService.class);
    private static final String EXTRA_SLOT_DESCRIPTION = "Zweiter Slot erstellt von %s";
    /** Keep in sync with the "pickups.delayInfo" text shown on the page. */
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(10);
    private static final int MAX_PAYLOAD_LENGTH = 32600;

    private final CurrentActorService currentActorService;
    private final BezirkService bezirkService;
    private final UserRepository userRepository;
    private final FoodsharingMyPickupsStoreCacheRepository cacheRepository;
    private final FoodsharingPickupExtraSlotRepository extraSlotRepository;
    private final FoodsharingClient client;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate refreshTransaction;
    private final Object refreshLock = new Object();
    /** Managed stores per account, so the store list is not requested on every page view. */
    private final Map<String, ManagedStores> managedStoresByAccount = new ConcurrentHashMap<>();

    public FoodsharingMyPickupsService(CurrentActorService currentActorService, BezirkService bezirkService, UserRepository userRepository,
            FoodsharingMyPickupsStoreCacheRepository cacheRepository, FoodsharingPickupExtraSlotRepository extraSlotRepository,
            FoodsharingClient client, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.currentActorService = currentActorService;
        this.bezirkService = bezirkService;
        this.userRepository = userRepository;
        this.cacheRepository = cacheRepository;
        this.extraSlotRepository = extraSlotRepository;
        this.client = client;
        this.objectMapper = objectMapper;
        this.refreshTransaction = new TransactionTemplate(transactionManager);
        this.refreshTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Not transactional on purpose: no database connection is held while waiting for a running refresh. */
    public List<StorePickupsView> myPickups(String bezirkSlug) {
        String foodsharingUserId = currentActorService.requireFoodsharingId();
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        String accountKey = client.accountKey(bezirk);
        List<FoodsharingPickupModels.Store> stores = refreshStaleStores(bezirk, accountKey);
        return buildMyPickups(accountKey, stores, foodsharingUserId);
    }

    @Transactional
    public List<StorePickupsView> addExtraSlot(String bezirkSlug, long storeId, Instant pickupDate) {
        String foodsharingUserId = currentActorService.requireFoodsharingId();
        if (pickupDate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Pickup date is required."));
        }
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        String accountKey = client.accountKey(bezirk);
        FoodsharingMyPickupsStoreCache cache = cacheRepository.findByAccountKeyAndStoreId(accountKey, storeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Store " + storeId + " is not known.")));
        List<FoodsharingPickupModels.Pickup> pickups = deserialize(cache.getPayloadJson());
        FoodsharingPickupModels.Pickup pickup = pickups.stream()
                .filter(candidate -> candidate.date().equals(pickupDate))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Pickup not found.")));
        if (!pickup.date().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of("The pickup is already over."));
        }
        if (!isFirst(pickup, foodsharingUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCESS_DENIED, List.of("Only the first person of a pickup can add a slot."));
        }
        if (extraSlotRepository.existsByStoreIdAndPickupDate(storeId, pickupDate)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of("A slot was already added to this pickup."));
        }
        String userName = userRepository.findByFoodsharingIdIgnoreCase(foodsharingUserId).map(User::getName)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> pickup.users().get(0).name());
        int previousTotalSlots = Math.max(pickup.totalSlots(), pickup.users().size());
        int newTotalSlots = previousTotalSlots + 1;

        // Persist first: the unique constraint protects against double clicks; a failing Foodsharing call rolls this back.
        FoodsharingPickupExtraSlot extraSlot = new FoodsharingPickupExtraSlot();
        extraSlot.setAccountKey(accountKey);
        extraSlot.setStoreId(storeId);
        extraSlot.setPickupDate(pickupDate);
        extraSlot.setFoodsharingUserId(foodsharingUserId);
        extraSlot.setFoodsharingUserName(userName);
        extraSlot.setPreviousTotalSlots(previousTotalSlots);
        extraSlot.setNewTotalSlots(newTotalSlots);
        extraSlotRepository.saveAndFlush(extraSlot);

        try {
            client.updatePickupSlots(bezirk, storeId, pickupDate, newTotalSlots, EXTRA_SLOT_DESCRIPTION.formatted(userName));
        } catch (RestClientException exception) {
            log.warn("Adding an extra slot failed storeId={} pickupDate={} userId={} account={}: {}",
                    storeId, pickupDate, foodsharingUserId, accountKey, exception.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, ApiErrorCode.UNEXPECTED_ERROR, List.of("Foodsharing did not accept the new slot."));
        }
        log.info("Extra slot added storeId={} pickupDate={} userId={} account={} totalSlots {} -> {}",
                storeId, pickupDate, foodsharingUserId, accountKey, previousTotalSlots, newTotalSlots);

        // Keep the cache in sync instead of re-fetching the store from Foodsharing.
        cache.setPayloadJson(serialize(pickups.stream()
                .map(candidate -> candidate.date().equals(pickupDate)
                        ? new FoodsharingPickupModels.Pickup(candidate.storeId(), candidate.date(), newTotalSlots, candidate.users())
                        : candidate)
                .toList()));
        cacheRepository.save(cache);

        ManagedStores managed = managedStoresByAccount.get(accountKey);
        return buildMyPickups(accountKey, managed == null ? List.of() : managed.stores(), foodsharingUserId);
    }

    /**
     * Reloads stores of the account whose cached pickups are older than {@link #REFRESH_AFTER}. Runs in its own
     * committed transaction under a lock so parallel page views do not fetch the same store twice. Failures are
     * logged and the older cached data is shown instead.
     *
     * @return the stores the account is responsible for (empty when unknown)
     */
    private List<FoodsharingPickupModels.Store> refreshStaleStores(Bezirk bezirk, String accountKey) {
        synchronized (refreshLock) {
            Instant now = Instant.now();
            List<FoodsharingPickupModels.Store> stores = managedStores(bezirk, accountKey, now);
            try {
                refreshTransaction.executeWithoutResult(status -> {
                    Map<Long, FoodsharingMyPickupsStoreCache> caches = cacheRepository.findAllByAccountKey(accountKey).stream()
                            .collect(Collectors.toMap(FoodsharingMyPickupsStoreCache::getStoreId, cache -> cache, (left, right) -> left));
                    Instant staleBefore = now.minus(REFRESH_AFTER);
                    for (FoodsharingPickupModels.Store store : stores) {
                        FoodsharingMyPickupsStoreCache cache = caches.get(store.id());
                        if (cache != null && cache.getRefreshedAt().isAfter(staleBefore)) {
                            continue;
                        }
                        try {
                            String payload = serialize(client.pickups(bezirk, store.id()));
                            if (payload.length() > MAX_PAYLOAD_LENGTH) {
                                log.warn("Pickups of storeId={} are too large to cache ({} chars); skipping.", store.id(), payload.length());
                                continue;
                            }
                            if (cache == null) {
                                cache = new FoodsharingMyPickupsStoreCache();
                                cache.setAccountKey(accountKey);
                                cache.setStoreId(store.id());
                            }
                            cache.setStoreName(store.name() == null ? "" : truncate(store.name(), 255));
                            cache.setRefreshedAt(now);
                            cache.setPayloadJson(payload);
                            cacheRepository.save(cache);
                        } catch (RuntimeException exception) {
                            log.warn("Could not refresh pickups storeId={} account={}: {}", store.id(), accountKey, exception.getMessage());
                        }
                    }
                });
            } catch (RuntimeException exception) {
                log.warn("Refreshing pickups for the pickups page failed; showing cached data: {}", exception.getMessage());
            }
            return stores;
        }
    }

    private List<FoodsharingPickupModels.Store> managedStores(Bezirk bezirk, String accountKey, Instant now) {
        ManagedStores cached = managedStoresByAccount.get(accountKey);
        if (cached != null && cached.loadedAt().isAfter(now.minus(REFRESH_AFTER))) {
            return cached.stores();
        }
        try {
            List<FoodsharingPickupModels.Store> stores = client.stores(bezirk).stream()
                    .filter(FoodsharingPickupModels.Store::isManaging)
                    .toList();
            managedStoresByAccount.put(accountKey, new ManagedStores(now, stores));
            return stores;
        } catch (RuntimeException exception) {
            log.warn("Could not load managed stores for account={}: {}", accountKey, exception.getMessage());
            return cached == null ? List.of() : cached.stores();
        }
    }

    private List<StorePickupsView> buildMyPickups(String accountKey, List<FoodsharingPickupModels.Store> managedStores, String foodsharingUserId) {
        Instant now = Instant.now();
        Set<Long> managedStoreIds = managedStores.stream().map(FoodsharingPickupModels.Store::id).collect(Collectors.toSet());
        // If the store list could not be loaded, fall back to everything cached for this account.
        List<FoodsharingMyPickupsStoreCache> caches = cacheRepository.findAllByAccountKey(accountKey).stream()
                .filter(cache -> managedStoreIds.isEmpty() || managedStoreIds.contains(cache.getStoreId()))
                .toList();
        Map<String, FoodsharingPickupExtraSlot> extraSlots = new HashMap<>();
        if (!caches.isEmpty()) {
            for (FoodsharingPickupExtraSlot extraSlot : extraSlotRepository.findAllByStoreIdIn(
                    caches.stream().map(FoodsharingMyPickupsStoreCache::getStoreId).toList())) {
                extraSlots.put(key(extraSlot.getStoreId(), extraSlot.getPickupDate()), extraSlot);
            }
        }
        List<StorePickupsView> result = new ArrayList<>();
        for (FoodsharingMyPickupsStoreCache cache : caches) {
            List<FoodsharingPickupModels.Pickup> storePickups;
            try {
                storePickups = deserialize(cache.getPayloadJson());
            } catch (RuntimeException exception) {
                log.warn("Skipping unreadable pickups cache storeId={}", cache.getStoreId());
                continue;
            }
            List<PickupView> pickups = storePickups.stream()
                    .filter(pickup -> isParticipant(pickup, foodsharingUserId))
                    .sorted(Comparator.comparing(FoodsharingPickupModels.Pickup::date).reversed())
                    .map(pickup -> view(pickup, foodsharingUserId, extraSlots.get(key(cache.getStoreId(), pickup.date())), now))
                    .toList();
            if (!pickups.isEmpty()) {
                String storeName = cache.getStoreName() == null || cache.getStoreName().isBlank() ? "#" + cache.getStoreId() : cache.getStoreName();
                result.add(new StorePickupsView(cache.getStoreId(), storeName, cache.getRefreshedAt(), pickups));
            }
        }
        result.sort(Comparator.comparing(StorePickupsView::storeName, String.CASE_INSENSITIVE_ORDER).thenComparingLong(StorePickupsView::storeId));
        return result;
    }

    /** Extra slots that users added on the pickups page with the Bezirk's Foodsharing account, newest first. */
    @Transactional(readOnly = true)
    public List<ExtraSlotAuditView> extraSlotAudit(String bezirkSlug) {
        User user = currentActorService.requireAutomationUser();
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        if (!user.isCanManageUsers() && (user.getBezirk() == null || !user.getBezirk().getId().equals(bezirk.getId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND);
        }
        String accountKey = client.accountKey(bezirk);
        Map<Long, String> storeNames = new HashMap<>();
        Map<String, FoodsharingPickupModels.Pickup> cachedPickups = new HashMap<>();
        for (FoodsharingMyPickupsStoreCache cache : cacheRepository.findAllByAccountKey(accountKey)) {
            if (cache.getStoreName() != null && !cache.getStoreName().isBlank()) {
                storeNames.put(cache.getStoreId(), cache.getStoreName());
            }
            try {
                for (FoodsharingPickupModels.Pickup pickup : deserialize(cache.getPayloadJson())) {
                    cachedPickups.put(key(cache.getStoreId(), pickup.date()), pickup);
                }
            } catch (RuntimeException exception) {
                log.warn("Skipping unreadable pickups cache storeId={}", cache.getStoreId());
            }
        }
        return extraSlotRepository.findTop200ByAccountKeyOrderByCreatedAtDesc(accountKey).stream()
                .map(extraSlot -> new ExtraSlotAuditView(extraSlot.getStoreId(),
                        storeNames.getOrDefault(extraSlot.getStoreId(), "#" + extraSlot.getStoreId()),
                        extraSlot.getPickupDate(), extraSlot.getFoodsharingUserId(), extraSlot.getFoodsharingUserName(),
                        extraSlot.getPreviousTotalSlots(), extraSlot.getNewTotalSlots(), extraSlot.getCreatedAt(),
                        filled(cachedPickups.get(key(extraSlot.getStoreId(), extraSlot.getPickupDate())), extraSlot.getNewTotalSlots())))
                .toList();
    }

    /** Whether every slot of the pickup is taken, as far as the cached pickup shows; null if it is not cached. */
    private static Boolean filled(FoodsharingPickupModels.Pickup pickup, int totalSlots) {
        return pickup == null ? null : pickup.users().size() >= Math.max(totalSlots, pickup.totalSlots());
    }

    private PickupView view(FoodsharingPickupModels.Pickup pickup, String foodsharingUserId, FoodsharingPickupExtraSlot extraSlot, Instant now) {
        boolean first = isFirst(pickup, foodsharingUserId);
        boolean confirmed = pickup.users().stream().filter(user -> sameUser(user.id(), foodsharingUserId)).anyMatch(FoodsharingPickupModels.PickupUser::confirmed);
        boolean canAddExtraSlot = first && extraSlot == null && pickup.date().isAfter(now);
        int totalSlots = Math.max(pickup.totalSlots(), extraSlot == null ? 0 : extraSlot.getNewTotalSlots());
        return new PickupView(pickup.date(), totalSlots, pickup.users().size(), confirmed, first, canAddExtraSlot,
                extraSlot != null, extraSlot == null ? null : extraSlot.getFoodsharingUserName(),
                pickup.users().stream().map(FoodsharingPickupModels.PickupUser::name).toList());
    }

    private static boolean isParticipant(FoodsharingPickupModels.Pickup pickup, String foodsharingUserId) {
        return pickup.users().stream().anyMatch(user -> sameUser(user.id(), foodsharingUserId));
    }

    private static boolean isFirst(FoodsharingPickupModels.Pickup pickup, String foodsharingUserId) {
        return !pickup.users().isEmpty() && sameUser(pickup.users().get(0).id(), foodsharingUserId);
    }

    private static boolean sameUser(String left, String right) {
        return left != null && right != null && Objects.equals(left.trim(), right.trim());
    }

    private static String key(long storeId, Instant pickupDate) {
        return storeId + "|" + pickupDate;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private List<FoodsharingPickupModels.Pickup> deserialize(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<List<FoodsharingPickupModels.Pickup>>() {});
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not read cached store pickups."));
        }
    }

    private String serialize(List<FoodsharingPickupModels.Pickup> pickups) {
        try {
            return objectMapper.writeValueAsString(pickups);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not store cached store pickups."));
        }
    }

    private record ManagedStores(Instant loadedAt, List<FoodsharingPickupModels.Store> stores) {}

    public record StorePickupsView(long storeId, String storeName, Instant refreshedAt, List<PickupView> pickups) {}

    public record ExtraSlotAuditView(long storeId, String storeName, Instant pickupDate, String foodsharingUserId,
            String foodsharingUserName, int previousTotalSlots, int newTotalSlots, Instant createdAt, Boolean filled) {}

    public record PickupView(Instant pickupDate, int totalSlots, int occupiedSlots, boolean confirmed, boolean firstInSlot,
            boolean canAddExtraSlot, boolean extraSlotAdded, String extraSlotAddedBy, List<String> participantNames) {}
}
