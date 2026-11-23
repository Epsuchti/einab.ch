package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.repository.BezirkRepository;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BezirkService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final BezirkRepository bezirkRepository;
    private final FoodsharingStoreAutomationRepository storeAutomationRepository;
    private final FoodsharingBezirkMessageConnectionService messageConnectionService;
    private final FoodsharingClient foodsharingClient;
    private final UserRepository userRepository;
    private final EinAbRepository einAbRepository;
    private final SlotRepository slotRepository;

    public BezirkService(BezirkRepository bezirkRepository,
                         FoodsharingStoreAutomationRepository storeAutomationRepository,
                         FoodsharingBezirkMessageConnectionService messageConnectionService,
                         FoodsharingClient foodsharingClient,
                         UserRepository userRepository,
                         EinAbRepository einAbRepository,
                         SlotRepository slotRepository) {
        this.userRepository = userRepository;
        this.einAbRepository = einAbRepository;
        this.slotRepository = slotRepository;
        this.bezirkRepository = bezirkRepository;
        this.storeAutomationRepository = storeAutomationRepository;
        this.messageConnectionService = messageConnectionService;
        this.foodsharingClient = foodsharingClient;
    }

    @Transactional(readOnly = true)
    public List<Bezirk> findAllActive() {
        return bezirkRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Bezirk> findAllActiveWithDisabledCategories() {
        return bezirkRepository.findWithDisabledCategoriesByActiveTrueOrderBySortOrderAscNameAsc();
    }

    /**
     * Rejects categories the Bezirk has disabled. Existing EinAbs may keep such a category, so callers pass the
     * current category to allow saving them unchanged.
     */
    public void requireEnabledCategory(Bezirk bezirk, EinAbCategory category, EinAbCategory currentCategory) {
        if (category != currentCategory && bezirk.getDisabledCategories().contains(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.CATEGORY_DISABLED);
        }
    }

    @Transactional(readOnly = true)
    public Bezirk requireActive(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND);
        }
        return bezirkRepository.findWithIgnoredCategoriesBySlugAndActiveTrue(slug.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Bezirk> findAllForAdmin() {
        return bezirkRepository.findAllByOrderByActiveDescSortOrderAscNameAsc();
    }

    /** New Bezirke start with the default booking rules and are listed after the existing ones. */
    @Transactional
    public Bezirk create(String name, String slug) {
        String normalizedName = name == null ? "" : name.trim();
        String normalizedSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (normalizedName.isEmpty() || normalizedName.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Name must be between 1 and 200 characters."));
        }
        if (normalizedSlug.length() < 2 || normalizedSlug.length() > 100
                || !SLUG_PATTERN.matcher(normalizedSlug).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Slug may only contain lowercase letters, digits and single hyphens."));
        }
        if (bezirkRepository.existsBySlug(normalizedSlug)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BEZIRK_SLUG_TAKEN);
        }
        Bezirk bezirk = new Bezirk();
        bezirk.setName(normalizedName);
        bezirk.setSlug(normalizedSlug);
        bezirk.setSortOrder(bezirkRepository.findFirstByOrderBySortOrderDesc()
                .map(last -> last.getSortOrder() + 1)
                .orElse(0));
        bezirk.setActive(true);
        return bezirkRepository.save(bezirk);
    }

    /** At most this many people and EinAbs are listed in the warning before disabling a Bezirk. */
    private static final int DISABLE_IMPACT_LIST_LIMIT = 100;

    /** What disabling a Bezirk leaves behind: its people and its upcoming EinAbs with the people booked into them. */
    public record DisableImpact(int assignedUserCount,
                                List<User> assignedUsers,
                                long upcomingEinAbCount,
                                List<EinAb> upcomingEinAbs,
                                Map<UUID, List<User>> bookedUsersByEinAbId) {
    }

    @Transactional(readOnly = true)
    public DisableImpact disableImpact(String slug) {
        String normalizedSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        Bezirk bezirk = bezirkRepository.findBySlug(normalizedSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND));
        List<User> assignedUsers = userRepository.findAllByBezirk(bezirk).stream()
                .filter(User::isActive)
                .sorted(Comparator.comparing(user -> user.getName() == null ? "" : user.getName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        Page<EinAb> upcoming = einAbRepository.findAllByBezirkAndStartDateTimeGreaterThanEqualOrderByStartDateTimeAsc(
                bezirk, Instant.now(), PageRequest.of(0, DISABLE_IMPACT_LIST_LIMIT));
        Map<UUID, List<User>> bookedUsers = new LinkedHashMap<>();
        if (!upcoming.isEmpty()) {
            for (Slot slot : slotRepository.findAllByEinAbInAndStatusInOrderByBookedAtAsc(
                    upcoming.getContent(), List.of(SlotStatus.BOOKED))) {
                if (slot.getBookingUser() != null) {
                    bookedUsers.computeIfAbsent(slot.getEinAb().getId(), id -> new java.util.ArrayList<>())
                            .add(slot.getBookingUser());
                }
            }
        }
        return new DisableImpact(assignedUsers.size(),
                assignedUsers.stream().limit(DISABLE_IMPACT_LIST_LIMIT).toList(),
                upcoming.getTotalElements(),
                upcoming.getContent(),
                bookedUsers);
    }

    /**
     * Disabled Bezirke disappear from the Bezirk selection and their pages; EinAbs, bookings and users stay
     * untouched so the Bezirk can be enabled again later.
     */
    @Transactional
    public Bezirk setActive(String slug, boolean active) {
        String normalizedSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        Bezirk bezirk = bezirkRepository.findBySlug(normalizedSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND));
        if (bezirk.isActive() == active) {
            return bezirk;
        }
        if (!active && bezirkRepository.countByActiveTrue() <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.LAST_ACTIVE_BEZIRK);
        }
        bezirk.setActive(active);
        return bezirkRepository.save(bezirk);
    }

    @Transactional
    public Bezirk updateSettings(String slug,
                                 Long cleaningStoreId,
                                 Boolean preventDuplicateTeacherBookings,
                                 Boolean preventDuplicateCategoryBookings,
                                 Integer maxActiveBookingsPerUser,
                                 Integer pickupEinAbLeadMinutes,
                                 Boolean requireFairteilerVisitByThirdEinAb,
                                 Set<EinAbCategory> ignoredCategories,
                                 Set<EinAbCategory> disabledCategories,
                                 Boolean useFoodsharingUserInfoApi,
                                 String foodsharingMessagingEmail,
                                 String foodsharingMessagingPassword,
                                 Boolean infoStepEnabled,
                                 Boolean infoStepOnlyUnverified,
                                 String infoMarkdown,
                                 String infoMarkdownEn,
                                 String infoMarkdownGws) {
        if (java.util.stream.Stream.of(infoMarkdown, infoMarkdownEn, infoMarkdownGws)
                .anyMatch(text -> text != null && text.length() > 1_000_000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("The Bezirk info is too long."));
        }
        if (cleaningStoreId != null && cleaningStoreId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Cleaning store ID must be positive."));
        }
        if (maxActiveBookingsPerUser != null && maxActiveBookingsPerUser < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Maximum active bookings per user must be positive."));
        }
        if (pickupEinAbLeadMinutes != null && (pickupEinAbLeadMinutes < 0 || pickupEinAbLeadMinutes > 120)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Minutes before the pickup must be between 0 and 120."));
        }
        Bezirk bezirk = requireActive(slug);
        if (cleaningStoreId != null
                && bezirkRepository.existsByCleaningStoreIdAndIdNot(cleaningStoreId, bezirk.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Cleaning store ID is already assigned to another Bezirk."));
        }
        bezirk.setCleaningStoreId(cleaningStoreId);
        if (preventDuplicateTeacherBookings != null) {
            bezirk.setPreventDuplicateTeacherBookings(preventDuplicateTeacherBookings);
        }
        if (preventDuplicateCategoryBookings != null) {
            bezirk.setPreventDuplicateCategoryBookings(preventDuplicateCategoryBookings);
        }
        if (maxActiveBookingsPerUser != null) {
            bezirk.setMaxActiveBookingsPerUser(maxActiveBookingsPerUser);
        }
        if (pickupEinAbLeadMinutes != null) {
            bezirk.setPickupEinAbLeadMinutes(pickupEinAbLeadMinutes);
        }
        if (requireFairteilerVisitByThirdEinAb != null) {
            bezirk.setRequireFairteilerVisitByThirdEinAb(requireFairteilerVisitByThirdEinAb);
        }
        if (useFoodsharingUserInfoApi != null) {
            bezirk.setUseFoodsharingUserInfoApi(useFoodsharingUserInfoApi);
        }
        if (ignoredCategories != null) {
            bezirk.setIgnoredCategories(ignoredCategories);
        }
        if (disabledCategories != null) {
            bezirk.setDisabledCategories(disabledCategories);
        }
        if (infoMarkdown != null) {
            bezirk.setInfoMarkdown(infoMarkdown.isBlank() ? null : infoMarkdown);
        }
        if (infoMarkdownEn != null) {
            bezirk.setInfoMarkdownEn(infoMarkdownEn.isBlank() ? null : infoMarkdownEn);
        }
        if (infoMarkdownGws != null) {
            bezirk.setInfoMarkdownGws(infoMarkdownGws.isBlank() ? null : infoMarkdownGws);
        }
        if (infoStepEnabled != null) {
            bezirk.setInfoStepEnabled(infoStepEnabled);
        }
        if (infoStepOnlyUnverified != null) {
            bezirk.setInfoStepOnlyUnverified(infoStepOnlyUnverified);
        }
        if (cleaningStoreId == null) {
            storeAutomationRepository.findAllByBezirkAndCleaningRuleEnabledTrue(bezirk)
                    .forEach(automation -> automation.setCleaningRuleEnabled(false));
        }
        messageConnectionService.update(bezirk, foodsharingMessagingEmail, foodsharingMessagingPassword);
        if (!foodsharingClient.hasUserInfoSource(bezirk)) {
            storeAutomationRepository.findAllByBezirkAndCleaningRuleEnabledTrue(bezirk)
                    .forEach(automation -> automation.setCleaningRuleEnabled(false));
        }
        return bezirkRepository.save(bezirk);
    }
}
