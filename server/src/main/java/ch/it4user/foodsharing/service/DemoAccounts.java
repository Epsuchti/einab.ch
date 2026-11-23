package ch.it4user.foodsharing.service;

import java.util.List;
import java.util.Optional;

/**
 * Demo accounts for app testers and store reviewers on the test server (see {@link DemoLoginService}).
 * <p>
 * Their Foodsharing IDs lie in a reserved range far above any real Foodsharing ID, so they can never belong to a
 * real person. Every Foodsharing call for one of them (user lookup, verification, message) is skipped, in every
 * environment, so the accounts never reach foodsharing.network and never break on a missing Foodsharing profile.
 */
public final class DemoAccounts {

    /** Every Foodsharing ID in this range is a demo ID; real Foodsharing IDs are 7 digits at most. */
    private static final long FIRST_DEMO_ID = 9_900_000_000L;
    private static final long LAST_DEMO_ID = 9_900_999_999L;
    /** Accounts created with one tap ({@link DemoLoginService#createAccount}) get the next free ID from here. */
    public static final long FIRST_GENERATED_ID = 9_900_100_000L;
    public static final long LAST_GENERATED_ID = LAST_DEMO_ID;

    public enum Role { TEACHER, NEWCOMER }

    public record Account(String foodsharingId, String name) {
    }

    /**
     * EinAb-Geber:innen without a login who own the seeded demo EinAbs, so testers who test as Neuling always find
     * something to book. Testers themselves get new accounts from {@link #FIRST_GENERATED_ID} up.
     */
    public static final List<Account> SEED_TEACHERS = List.of(
            new Account("9900000001", "Demo-EinAb-Geber:in 1"),
            new Account("9900000002", "Demo-EinAb-Geber:in 2"),
            new Account("9900000003", "Demo-EinAb-Geber:in 3"),
            new Account("9900000004", "Demo-EinAb-Geber:in 4"));

    private DemoAccounts() {
    }

    /** Whether the ID lies in the reserved demo range (seed teachers and generated accounts). */
    public static boolean isDemoId(String foodsharingId) {
        if (foodsharingId == null) {
            return false;
        }
        String trimmed = foodsharingId.trim();
        if (!trimmed.matches("\\d{10}")) {
            return false;
        }
        long id = Long.parseLong(trimmed);
        return id >= FIRST_DEMO_ID && id <= LAST_DEMO_ID;
    }

    public static Optional<Account> find(String foodsharingId) {
        if (!isDemoId(foodsharingId)) {
            return Optional.empty();
        }
        String trimmed = foodsharingId.trim();
        return SEED_TEACHERS.stream().filter(account -> account.foodsharingId().equals(trimmed)).findFirst();
    }

    /** What Foodsharing would report for a demo account, without asking it: name only, verification unknown. */
    public static FoodsharingUserInfo userInfo(String foodsharingId) {
        String name = find(foodsharingId).map(Account::name).orElse(null);
        return new FoodsharingUserInfo(foodsharingId.trim(), name, null, false, null, null, null);
    }
}
