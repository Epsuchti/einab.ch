package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Newcomer progress towards verification, computed from the EinAb bookings of a Bezirk.
 * The number of EinAbs a newcomer needs is the Bezirk's booking limit ({@code maxActiveBookingsPerUser}),
 * EinAbs in ignored categories do not count, and a Fairteiler visit is only required when the Bezirk has
 * {@code requireFairteilerVisitByThirdEinAb} switched on.
 */
@Service
public class NewcomerProgressService {

    /** A newcomer without an upcoming EinAb and no progress for this long counts as stalled. */
    static final Duration STALLED_AFTER = Duration.ofDays(30);
    /** Open EinAb places are counted this far ahead for the gap analysis. */
    static final Duration GAP_HORIZON = Duration.ofDays(30);
    /** A newcomer who still needs EinAbs and has not progressed for this long counts as waiting long. */
    static final Duration WAITING_LONG_AFTER = Duration.ofDays(14);

    private static final List<SlotStatus> COUNTING_STATUSES = List.of(SlotStatus.BOOKED, SlotStatus.DONE);

    private final BezirkService bezirkService;
    private final BookingUserService bookingUserService;
    private final EinAbRepository einAbRepository;
    private final SlotRepository slotRepository;
    private final UserRepository userRepository;

    public NewcomerProgressService(BezirkService bezirkService,
                                   BookingUserService bookingUserService,
                                   EinAbRepository einAbRepository,
                                   SlotRepository slotRepository,
                                   UserRepository userRepository) {
        this.bezirkService = bezirkService;
        this.bookingUserService = bookingUserService;
        this.einAbRepository = einAbRepository;
        this.slotRepository = slotRepository;
        this.userRepository = userRepository;
    }

    public enum Stage {
        REGISTERED,
        FIRST_BOOKED,
        IN_PROGRESS,
        FAIRTEILER_MISSING,
        COMPLETE,
        VERIFIED
    }

    public enum NextStep {
        BOOK_EINAB,
        BOOK_FAIRTEILER_EINAB,
        ATTEND_BOOKED,
        WAIT_FOR_VERIFICATION,
        DONE
    }

    public enum GapSeverity {
        MISSING,
        LOW,
        OK
    }

    public enum NeedKind {
        /** An EinAb of the need's category would help. */
        CATEGORY,
        /** An EinAb with a Fairteiler visit would help, in any category the newcomers may still book. */
        FAIRTEILER
    }

    public record Progress(User user,
                           int targetEinAbs,
                           int countedEinAbs,
                           int upcomingEinAbs,
                           boolean fairteilerRequired,
                           boolean fairteilerDone,
                           boolean fairteilerBooked,
                           Set<EinAbCategory> doneCategories,
                           Set<EinAbCategory> bookableCategories,
                           int doneEinAbs,
                           Set<UUID> bookedTeacherIds,
                           Set<UUID> bookedEinAbIds,
                           Instant lastActivityAt,
                           Stage stage,
                           NextStep nextStep,
                           boolean stalled) {

        int remainingToBook() {
            return Math.max(0, targetEinAbs - countedEinAbs - upcomingEinAbs);
        }

        boolean needsFairteilerBooking() {
            return fairteilerRequired && !fairteilerDone && !fairteilerBooked;
        }

        boolean inTraining() {
            return stage != Stage.COMPLETE && stage != Stage.VERIFIED;
        }

        /** The booking rules only let this newcomer book EinAbs with a Fairteiler visit next. */
        boolean restrictedToFairteiler() {
            return nextStep == NextStep.BOOK_FAIRTEILER_EINAB;
        }
    }

    /** One step of the funnel. {@code einAbCount} is set for IN_PROGRESS steps only; {@code users} is empty unless names were requested. */
    public record FunnelStep(Stage stage,
                             Integer einAbCount,
                             int userCount,
                             int reachedCount,
                             int stalledCount,
                             Integer medianDaysInStep,
                             List<Progress> users) {
    }

    public record Gap(EinAbCategory category,
                      boolean withFairteiler,
                      int students,
                      int openSlots,
                      GapSeverity severity) {
    }

    /**
     * What one more EinAb would do for the newcomers. {@code newcomers} counts the newcomers in training who still need
     * EinAbs and could book it, {@code withoutPlace} those of them who cannot book any open place at the moment, and
     * {@code waitingLong} those of them without progress for {@link #WAITING_LONG_AFTER}.
     */
    public record Need(NeedKind kind,
                       EinAbCategory category,
                       int newcomers,
                       int withoutPlace,
                       int waitingLong,
                       int openSlots,
                       GapSeverity severity) {
    }

    public record Funnel(int targetEinAbs,
                         boolean fairteilerRequired,
                         Integer days,
                         int totalUsers,
                         int inTrainingUsers,
                         int stalledUsers,
                         int einAbsStillNeeded,
                         int openSlots,
                         int fairteilerVisitsStillNeeded,
                         int openFairteilerSlots,
                         int gapHorizonDays,
                         int newcomersNeedingEinAbs,
                         int newcomersWithoutPlace,
                         int waitingLongDays,
                         List<FunnelStep> steps,
                         List<Gap> gaps,
                         List<Need> needs,
                         Set<EinAbCategory> viewerCategories) {
    }

    @Transactional(readOnly = true)
    public Progress getMyProgress(String bezirkSlug, String foodsharingId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        User user = bookingUserService.getByFoodsharingId(foodsharingId);
        if (user.getBezirk() != null && user.getBezirk().isActive()
                && !user.getBezirk().getId().equals(bezirk.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_BEZIRK_MISMATCH);
        }
        List<Slot> bookings = slotRepository.findAllByBookingUserAndStatusesAndBezirk(
                user, COUNTING_STATUSES, bezirk, PageRequest.of(0, 500)).getContent();
        return progress(user, bookings, bezirk, Instant.now());
    }

    /**
     * Funnel of the newcomers of a Bezirk. Newcomers are active users of the Bezirk who do not give EinAbs;
     * verified users without any EinAb in this tool are left out, since they were never newcomers here.
     *
     * @param days       only users registered in the last {@code days} days, or all when {@code null}
     * @param withNames  whether each step lists its users (admins) or only counts (EinAb givers)
     * @param viewer     the EinAb giver looking at the funnel, to mark the categories they have given EinAbs in
     */
    @Transactional(readOnly = true)
    public Funnel getFunnel(String bezirkSlug, Integer days, boolean withNames, Optional<User> viewer) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Instant now = Instant.now();
        Instant registeredAfter = days == null ? null : now.minus(Duration.ofDays(days));
        List<User> users = userRepository.findAllByBezirk(bezirk).stream()
                .filter(User::isActive)
                .filter(user -> !user.isCanGiveEinAbs())
                .filter(user -> registeredAfter == null || user.getCreatedAt().isAfter(registeredAfter))
                .toList();
        Map<UUID, List<Slot>> bookingsByUser = users.isEmpty()
                ? Map.of()
                : slotRepository.findAllByActiveBookingUsersAndStatusesAndBezirk(users, COUNTING_STATUSES, bezirk).stream()
                .collect(Collectors.groupingBy(slot -> slot.getBookingUser().getId()));
        List<Progress> progresses = users.stream()
                .filter(user -> !user.isVerified() || bookingsByUser.containsKey(user.getId()))
                .map(user -> progress(user, bookingsByUser.getOrDefault(user.getId(), List.of()), bezirk, now))
                .toList();

        int target = targetEinAbs(bezirk);
        boolean fairteilerRequired = bezirk.isRequireFairteilerVisitByThirdEinAb();
        List<FunnelStep> steps = buildSteps(progresses, target, fairteilerRequired, withNames, now);

        List<Slot> allOpenSlots = slotRepository.findAllByEinAbBezirkAndEinAbStartDateTimeBetween(bezirk, now, now.plus(GAP_HORIZON)).stream()
                .filter(slot -> slot.getStatus() == SlotStatus.AVAILABLE)
                .toList();
        List<Slot> openSlots = allOpenSlots.stream()
                .filter(slot -> !bezirk.getIgnoredCategories().contains(slot.getEinAb().getCategory()))
                .toList();
        List<Progress> inTraining = progresses.stream().filter(Progress::inTraining).toList();
        List<Progress> needingEinAbs = inTraining.stream().filter(progress -> progress.remainingToBook() > 0).toList();
        Set<UUID> withoutPlace = needingEinAbs.stream()
                .filter(progress -> openSlots.stream().noneMatch(slot -> canBook(bezirk, progress, slot)))
                .map(progress -> progress.user().getId())
                .collect(Collectors.toSet());
        Set<EinAbCategory> viewerCategories = viewer
                .map(user -> einAbRepository.findCategoriesByTeacherAndBezirk(user, bezirk))
                .<Set<EinAbCategory>>map(categories -> categories.isEmpty() ? EnumSet.noneOf(EinAbCategory.class) : EnumSet.copyOf(categories))
                .orElseGet(() -> EnumSet.noneOf(EinAbCategory.class));

        return new Funnel(
                target,
                fairteilerRequired,
                days,
                progresses.size(),
                inTraining.size(),
                (int) progresses.stream().filter(Progress::stalled).count(),
                inTraining.stream().mapToInt(Progress::remainingToBook).sum(),
                openSlots.size(),
                (int) inTraining.stream().filter(Progress::needsFairteilerBooking).count(),
                (int) allOpenSlots.stream().filter(slot -> slot.getEinAb().isVisitFairteiler()).count(),
                (int) GAP_HORIZON.toDays(),
                needingEinAbs.size(),
                withoutPlace.size(),
                (int) WAITING_LONG_AFTER.toDays(),
                steps,
                computeGaps(bezirk, inTraining, openSlots, fairteilerRequired),
                computeNeeds(bezirk, needingEinAbs, openSlots, withoutPlace, viewer, now),
                viewerCategories);
    }

    /**
     * Whether the newcomer may book this open place under the booking rules of the Bezirk (see PublicService):
     * category, Fairteiler visit, minimum pickups, one EinAb per giver and one place per EinAb.
     * Open places are never in ignored categories, so every rule applies.
     */
    private static boolean canBook(Bezirk bezirk, Progress progress, Slot slot) {
        Integer minimumPickupCount = slot.getEinAb().getMinimumPickupCount();
        return progress.bookableCategories().contains(slot.getEinAb().getCategory())
                && (!progress.restrictedToFairteiler() || slot.getEinAb().isVisitFairteiler())
                && (minimumPickupCount == null || progress.doneEinAbs() >= minimumPickupCount)
                && !blockedByGiver(bezirk, progress, slot.getTeacher())
                && !progress.bookedEinAbIds().contains(slot.getEinAb().getId());
    }

    /** With one EinAb per giver switched on, a newcomer cannot book a giver they already had. */
    private static boolean blockedByGiver(Bezirk bezirk, Progress progress, User teacher) {
        return bezirk.isPreventDuplicateTeacherBookings()
                && teacher != null
                && progress.bookedTeacherIds().contains(teacher.getId());
    }

    /**
     * Ranks what an EinAb giver can offer: one need per category the newcomers may still book (an EinAb without a
     * Fairteiler visit, so newcomers who now need the Fairteiler visit are left out there) and one need for Fairteiler
     * visits. Needs that leave newcomers without any place come first, then those with the largest shortage.
     * When one EinAb per giver is switched on, newcomers who already had an EinAb with the viewer are left out,
     * since a new EinAb of the viewer would not help them.
     */
    private List<Need> computeNeeds(Bezirk bezirk, List<Progress> needingEinAbs, List<Slot> openSlots, Set<UUID> withoutPlace,
                                    Optional<User> viewer, Instant now) {
        // A new EinAb by the viewer only helps newcomers who may still book with them.
        List<Progress> reachable = viewer
                .map(user -> needingEinAbs.stream().filter(progress -> !blockedByGiver(bezirk, progress, user)).toList())
                .orElse(needingEinAbs);
        Instant waitingSince = now.minus(WAITING_LONG_AFTER);
        Map<EinAbCategory, Integer> slots = new EnumMap<>(EinAbCategory.class);
        openSlots.forEach(slot -> slots.merge(slot.getEinAb().getCategory(), 1, Integer::sum));

        List<Need> needs = new ArrayList<>();
        for (EinAbCategory category : EinAbCategory.values()) {
            List<Progress> helped = reachable.stream()
                    .filter(progress -> !progress.restrictedToFairteiler())
                    .filter(progress -> progress.bookableCategories().contains(category))
                    .toList();
            if (!helped.isEmpty()) {
                needs.add(need(NeedKind.CATEGORY, category, helped, withoutPlace, waitingSince, slots.getOrDefault(category, 0)));
            }
        }
        List<Progress> fairteiler = reachable.stream().filter(Progress::restrictedToFairteiler).toList();
        if (!fairteiler.isEmpty()) {
            int fairteilerSlots = (int) openSlots.stream().filter(slot -> slot.getEinAb().isVisitFairteiler()).count();
            needs.add(need(NeedKind.FAIRTEILER, null, fairteiler, withoutPlace, waitingSince, fairteilerSlots));
        }
        needs.sort(Comparator
                .comparing(Need::withoutPlace, Comparator.reverseOrder())
                .thenComparing(Need::severity)
                .thenComparing((Need need) -> need.newcomers() - need.openSlots(), Comparator.reverseOrder())
                .thenComparing(Need::waitingLong, Comparator.reverseOrder())
                .thenComparing(Need::kind));
        return needs;
    }

    private static Need need(NeedKind kind, EinAbCategory category, List<Progress> helped, Set<UUID> withoutPlace,
                             Instant waitingSince, int openSlots) {
        int stuck = (int) helped.stream().filter(progress -> withoutPlace.contains(progress.user().getId())).count();
        int waiting = (int) helped.stream().filter(progress -> progress.lastActivityAt().isBefore(waitingSince)).count();
        return new Need(kind, category, helped.size(), stuck, waiting, openSlots, severity(helped.size(), openSlots));
    }

    private Progress progress(User user, Collection<Slot> bookings, Bezirk bezirk, Instant now) {
        Set<EinAbCategory> ignored = bezirk.getIgnoredCategories();
        List<Slot> relevant = bookings.stream()
                .filter(slot -> COUNTING_STATUSES.contains(slot.getStatus()))
                .filter(slot -> !ignored.contains(slot.getEinAb().getCategory()))
                .toList();
        List<Slot> counted = relevant.stream().filter(slot -> slot.getEinAb().getStartDateTime().isBefore(now)).toList();
        List<Slot> upcoming = relevant.stream().filter(slot -> !slot.getEinAb().getStartDateTime().isBefore(now)).toList();

        int target = targetEinAbs(bezirk);
        boolean fairteilerRequired = bezirk.isRequireFairteilerVisitByThirdEinAb();
        // Like the booking rule, a Fairteiler visit also counts when it was part of an EinAb in an ignored category.
        List<Slot> fairteilerVisits = bookings.stream()
                .filter(slot -> COUNTING_STATUSES.contains(slot.getStatus()))
                .filter(slot -> slot.getEinAb().isVisitFairteiler())
                .toList();
        boolean fairteilerDone = fairteilerVisits.stream().anyMatch(slot -> slot.getEinAb().getStartDateTime().isBefore(now));
        boolean fairteilerBooked = !fairteilerDone && !fairteilerVisits.isEmpty();

        Set<EinAbCategory> doneCategories = EnumSet.noneOf(EinAbCategory.class);
        relevant.forEach(slot -> doneCategories.add(slot.getEinAb().getCategory()));
        Set<EinAbCategory> bookableCategories = EnumSet.allOf(EinAbCategory.class);
        bookableCategories.removeAll(ignored);
        bookableCategories.removeAll(bezirk.getDisabledCategories());
        if (bezirk.isPreventDuplicateCategoryBookings()) {
            bookableCategories.removeAll(doneCategories);
        }

        Instant lastActivityAt = relevant.stream()
                .map(slot -> slot.getEinAb().getStartDateTime().isBefore(now)
                        ? slot.getEinAb().getStartDateTime()
                        : slot.getBookedAt() == null ? slot.getCreatedAt() : slot.getBookedAt())
                .max(Comparator.naturalOrder())
                .orElse(user.getCreatedAt());

        Stage stage;
        if (user.isVerified()) {
            stage = Stage.VERIFIED;
        } else if (counted.size() >= target) {
            stage = fairteilerRequired && !fairteilerDone ? Stage.FAIRTEILER_MISSING : Stage.COMPLETE;
        } else if (!counted.isEmpty()) {
            stage = Stage.IN_PROGRESS;
        } else if (!upcoming.isEmpty()) {
            stage = Stage.FIRST_BOOKED;
        } else {
            stage = Stage.REGISTERED;
        }

        NextStep nextStep;
        if (stage == Stage.VERIFIED) {
            nextStep = NextStep.DONE;
        } else if (stage == Stage.COMPLETE) {
            nextStep = NextStep.WAIT_FOR_VERIFICATION;
        } else if (fairteilerRequired && !fairteilerDone && !fairteilerBooked
                && counted.size() + upcoming.size() >= target - 1) {
            nextStep = NextStep.BOOK_FAIRTEILER_EINAB;
        } else if (counted.size() + upcoming.size() < target) {
            nextStep = NextStep.BOOK_EINAB;
        } else {
            nextStep = NextStep.ATTEND_BOOKED;
        }

        boolean stalled = stage != Stage.VERIFIED && stage != Stage.COMPLETE
                && upcoming.isEmpty()
                && lastActivityAt.isBefore(now.minus(STALLED_AFTER));

        // Inputs of the remaining booking rules (minimum pickups, one EinAb per giver, one place per EinAb), as PublicService checks them.
        int doneEinAbs = (int) relevant.stream().filter(slot -> slot.getStatus() == SlotStatus.DONE).count();
        Set<UUID> bookedTeacherIds = relevant.stream()
                .map(Slot::getTeacher)
                .filter(java.util.Objects::nonNull)
                .map(User::getId)
                .collect(Collectors.toSet());
        Set<UUID> bookedEinAbIds = bookings.stream()
                .filter(slot -> COUNTING_STATUSES.contains(slot.getStatus()))
                .map(slot -> slot.getEinAb().getId())
                .collect(Collectors.toSet());

        return new Progress(user, target, counted.size(), upcoming.size(), fairteilerRequired, fairteilerDone,
                fairteilerBooked, doneCategories, bookableCategories, doneEinAbs, bookedTeacherIds, bookedEinAbIds,
                lastActivityAt, stage, nextStep, stalled);
    }

    private List<FunnelStep> buildSteps(List<Progress> progresses, int target, boolean fairteilerRequired,
                                        boolean withNames, Instant now) {
        Map<StepKey, List<Progress>> byStep = progresses.stream().collect(Collectors.groupingBy(this::stepKey));
        List<StepKey> keys = new ArrayList<>();
        keys.add(new StepKey(Stage.REGISTERED, null));
        keys.add(new StepKey(Stage.FIRST_BOOKED, null));
        for (int count = 1; count < target; count++) {
            keys.add(new StepKey(Stage.IN_PROGRESS, count));
        }
        // Booking rules normally prevent this state, so the step is only shown when someone is in it.
        if (fairteilerRequired && byStep.containsKey(new StepKey(Stage.FAIRTEILER_MISSING, null))) {
            keys.add(new StepKey(Stage.FAIRTEILER_MISSING, null));
        }
        keys.add(new StepKey(Stage.COMPLETE, null));
        keys.add(new StepKey(Stage.VERIFIED, null));

        List<FunnelStep> steps = new ArrayList<>();
        int reached = progresses.size();
        for (StepKey key : keys) {
            List<Progress> inStep = byStep.getOrDefault(key, List.of()).stream()
                    .sorted(Comparator.comparing(Progress::lastActivityAt))
                    .toList();
            steps.add(new FunnelStep(
                    key.stage(),
                    key.einAbCount(),
                    inStep.size(),
                    reached,
                    (int) inStep.stream().filter(Progress::stalled).count(),
                    medianDays(inStep, now),
                    withNames ? inStep : List.of()));
            reached -= inStep.size();
        }
        return steps;
    }

    private StepKey stepKey(Progress progress) {
        return progress.stage() == Stage.IN_PROGRESS
                ? new StepKey(Stage.IN_PROGRESS, Math.min(progress.countedEinAbs(), progress.targetEinAbs() - 1))
                : new StepKey(progress.stage(), null);
    }

    private Integer medianDays(List<Progress> progresses, Instant now) {
        if (progresses.isEmpty()) {
            return null;
        }
        List<Long> days = progresses.stream()
                .map(progress -> Duration.between(progress.lastActivityAt(), now).toDays())
                .map(value -> Math.max(0, value))
                .sorted()
                .toList();
        int middle = days.size() / 2;
        long median = days.size() % 2 == 1 ? days.get(middle) : (days.get(middle - 1) + days.get(middle)) / 2;
        return (int) median;
    }

    /**
     * Compares what newcomers in training can still book with the open EinAb places of the next {@link #GAP_HORIZON}.
     * A row per category counts the newcomers who still need EinAbs and may still book that category; a Fairteiler row
     * per category counts the newcomers who still need a Fairteiler visit.
     */
    private List<Gap> computeGaps(Bezirk bezirk, List<Progress> inTraining, List<Slot> openSlots, boolean fairteilerRequired) {
        Map<EinAbCategory, Integer> students = new EnumMap<>(EinAbCategory.class);
        Map<EinAbCategory, Integer> fairteilerStudents = new EnumMap<>(EinAbCategory.class);
        for (Progress progress : inTraining) {
            if (progress.remainingToBook() <= 0) {
                continue;
            }
            for (EinAbCategory category : progress.bookableCategories()) {
                students.merge(category, 1, Integer::sum);
                if (progress.needsFairteilerBooking() && category != EinAbCategory.ONLINE) {
                    fairteilerStudents.merge(category, 1, Integer::sum);
                }
            }
        }
        Map<EinAbCategory, Integer> slots = new EnumMap<>(EinAbCategory.class);
        Map<EinAbCategory, Integer> fairteilerSlots = new EnumMap<>(EinAbCategory.class);
        for (Slot slot : openSlots) {
            EinAbCategory category = slot.getEinAb().getCategory();
            slots.merge(category, 1, Integer::sum);
            if (slot.getEinAb().isVisitFairteiler()) {
                fairteilerSlots.merge(category, 1, Integer::sum);
            }
        }

        List<Gap> gaps = new ArrayList<>();
        for (EinAbCategory category : EinAbCategory.values()) {
            if (bezirk.getIgnoredCategories().contains(category)) {
                continue;
            }
            int demand = students.getOrDefault(category, 0);
            if (demand > 0) {
                int supply = slots.getOrDefault(category, 0);
                gaps.add(new Gap(category, false, demand, supply, severity(demand, supply)));
            }
            int fairteilerDemand = fairteilerStudents.getOrDefault(category, 0);
            if (fairteilerRequired && fairteilerDemand > 0) {
                int supply = fairteilerSlots.getOrDefault(category, 0);
                gaps.add(new Gap(category, true, fairteilerDemand, supply, severity(fairteilerDemand, supply)));
            }
        }
        gaps.sort(Comparator
                .comparing(Gap::severity)
                .thenComparing((Gap gap) -> gap.students() - gap.openSlots(), Comparator.reverseOrder())
                .thenComparing(Gap::category));
        return gaps;
    }

    private static GapSeverity severity(int students, int openSlots) {
        if (openSlots == 0) {
            return GapSeverity.MISSING;
        }
        return openSlots < students ? GapSeverity.LOW : GapSeverity.OK;
    }

    private static int targetEinAbs(Bezirk bezirk) {
        return Math.max(1, bezirk.getMaxActiveBookingsPerUser());
    }

    private record StepKey(Stage stage, Integer einAbCount) {
    }
}
