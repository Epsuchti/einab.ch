package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Decides which Foodsharing stores an automation account may automate: the stores it is responsible for and, when the
 * account is Botschafter (region admin), all stores with an established cooperation in the regions it is Botschafter
 * of. Foodsharing grants Botschafter the same store rights as a store manager ({@code mayEditStore}), except in
 * regions with a store coordination working group, which is checked with {@link #requireAutomatableStore}.
 */
@Service
public class FoodsharingStoreAccessService {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingStoreAccessService.class);
    private static final Duration AMBASSADOR_STORES_TTL = Duration.ofMinutes(10);

    private final FoodsharingPickupApiClient client;
    private final Map<UUID, AmbassadorStores> ambassadorStoresByConnection = new ConcurrentHashMap<>();

    public FoodsharingStoreAccessService(FoodsharingPickupApiClient client) {
        this.client = client;
    }

    /** Stores the account manages, followed by the Botschafter region stores it does not manage. */
    public List<FoodsharingPickupModels.Store> accessibleStores(FoodsharingAdminConnection connection) {
        Map<Long, FoodsharingPickupModels.Store> stores = new LinkedHashMap<>();
        client.stores(connection).stream()
                .filter(FoodsharingPickupModels.Store::isManaging)
                .forEach(store -> stores.put(store.id(), store));
        ambassadorStores(connection).forEach(store -> stores.putIfAbsent(store.id(), store));
        return List.copyOf(stores.values());
    }

    /**
     * Throws unless the account may create an automation for the store. Botschafter stores are confirmed with
     * Foodsharing's own store permissions, so a region whose stores are run by a coordination group is refused.
     */
    public void requireAutomatableStore(FoodsharingAdminConnection connection, long storeId) {
        FoodsharingPickupModels.Store store = accessibleStores(connection).stream()
                .filter(candidate -> candidate.id() == storeId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                        List.of("Automations are only possible for stores you are responsible for or Botschafter of.")));
        if (store.isManaging()) {
            return;
        }
        if (!client.mayEditStore(connection, storeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Foodsharing does not allow this account to manage the store (e.g. a store coordination group is responsible for it)."));
        }
    }

    /** Drops the cached Botschafter stores, e.g. after the account was (re)connected. */
    public void forget(FoodsharingAdminConnection connection) {
        if (connection.getId() != null) {
            ambassadorStoresByConnection.remove(connection.getId());
        }
    }

    private List<FoodsharingPickupModels.Store> ambassadorStores(FoodsharingAdminConnection connection) {
        UUID key = connection.getId();
        Instant now = Instant.now();
        AmbassadorStores cached = key == null ? null : ambassadorStoresByConnection.get(key);
        if (cached != null && cached.loadedAt().isAfter(now.minus(AMBASSADOR_STORES_TTL))) {
            return cached.stores();
        }
        List<Long> regionIds;
        try {
            regionIds = client.ambassadorRegionIds(connection);
        } catch (RuntimeException exception) {
            log.warn("Could not read Botschafter regions of account {}: {}", connection.getFoodsharingEmail(), exception.getMessage());
            return cached == null ? List.of() : cached.stores();
        }
        List<FoodsharingPickupModels.Store> stores = new ArrayList<>();
        for (long regionId : regionIds) {
            try {
                stores.addAll(client.regionStores(connection, regionId));
            } catch (RuntimeException exception) {
                log.warn("Could not load stores of Botschafter region {} for account {}: {}", regionId, connection.getFoodsharingEmail(), exception.getMessage());
                return cached == null ? List.copyOf(stores) : cached.stores();
            }
        }
        if (!regionIds.isEmpty()) {
            log.debug("Account {} is Botschafter of regions {} with {} stores", connection.getFoodsharingEmail(), regionIds, stores.size());
        }
        List<FoodsharingPickupModels.Store> result = List.copyOf(stores);
        if (key != null) {
            ambassadorStoresByConnection.put(key, new AmbassadorStores(now, result));
        }
        return result;
    }

    private record AmbassadorStores(Instant loadedAt, List<FoodsharingPickupModels.Store> stores) {}
}
