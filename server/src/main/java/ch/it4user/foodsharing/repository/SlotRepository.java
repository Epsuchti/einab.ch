package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

    /** Counted bookings whose EinAb started in the window, that have no comment yet and were not reminded about. */
    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "teacher", "bookingUser"})
    @Query("""
        select s from Slot s
        where s.status in :statuses
          and s.bookingUser is not null
          and s.bookingUser.active = true
          and s.commentReminderSentAt is null
          and s.einAb.startDateTime > :startedAfter
          and s.einAb.startDateTime <= :startedBefore
          and not exists (select c.id from BookingComment c where c.slot = s)
        order by s.einAb.startDateTime asc
        """)
    List<Slot> findAllAwaitingCommentReminder(@Param("statuses") Collection<SlotStatus> statuses,
                                              @Param("startedAfter") java.time.Instant startedAfter,
                                              @Param("startedBefore") java.time.Instant startedBefore);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk"})
    @Query("""
        select s from Slot s
        where s.status = ch.it4user.foodsharing.domain.enumtype.SlotStatus.AVAILABLE
          and s.einAb.bezirk = :bezirk
          and s.einAb.startDateTime > current_timestamp
          and (:category is null or s.einAb.category = :category)
          and (:visitFairteiler is null or s.einAb.visitFairteiler = :visitFairteiler)
          and (
            :bookingUser is null
            or not exists (
              select bookedEinAbSlot.id from Slot bookedEinAbSlot
              where bookedEinAbSlot.bookingUser = :bookingUser
                and bookedEinAbSlot.status in (
                  ch.it4user.foodsharing.domain.enumtype.SlotStatus.BOOKED,
                  ch.it4user.foodsharing.domain.enumtype.SlotStatus.DONE
                )
                and bookedEinAbSlot.einAb = s.einAb
            )
          )
          and (
            :bookingUser is null
            or s.einAb.bezirk.preventDuplicateTeacherBookings = false
            or s.einAb.category member of s.einAb.bezirk.ignoredCategories
            or not exists (
              select bookedSlot.id from Slot bookedSlot
              where bookedSlot.bookingUser = :bookingUser
                and bookedSlot.status in (
                  ch.it4user.foodsharing.domain.enumtype.SlotStatus.BOOKED,
                  ch.it4user.foodsharing.domain.enumtype.SlotStatus.DONE
                )
                and bookedSlot.einAb.bezirk = :bezirk
                and bookedSlot.teacher = s.teacher
                and bookedSlot.einAb.category not member of bookedSlot.einAb.bezirk.ignoredCategories
            )
          )
          and (
            :bookingUser is null
            or s.einAb.bezirk.preventDuplicateCategoryBookings = false
            or s.einAb.category member of s.einAb.bezirk.ignoredCategories
            or not exists (
              select bookedCategorySlot.id from Slot bookedCategorySlot
              where bookedCategorySlot.bookingUser = :bookingUser
                and bookedCategorySlot.status in (
                  ch.it4user.foodsharing.domain.enumtype.SlotStatus.BOOKED,
                  ch.it4user.foodsharing.domain.enumtype.SlotStatus.DONE
                )
                and bookedCategorySlot.einAb.bezirk = :bezirk
                and bookedCategorySlot.einAb.category = s.einAb.category
            )
          )
          and (
            :searchPattern is null
            or lower(s.einAb.teacher.name) like :searchPattern
            or lower(coalesce(s.einAb.publicLocation, '')) like :searchPattern
            or lower(coalesce(s.einAb.location, '')) like :searchPattern
          )
        order by s.einAb.startDateTime asc
        """)
    Page<Slot> findAvailableSlots(@Param("bezirk") Bezirk bezirk,
                                  @Param("searchPattern") String searchPattern,
                                  @Param("category") EinAbCategory category,
                                  @Param("visitFairteiler") Boolean visitFairteiler,
                                  @Param("bookingUser") User bookingUser,
                                  Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where s.id = :id
          and exists (
            select e.id from EinAb e
            where e = s.einAb and e.bezirk = :bezirk
          )
        """)
    Optional<Slot> findForUpdateByIdAndBezirk(@Param("id") UUID id, @Param("bezirk") Bezirk bezirk);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    Optional<Slot> findByIdAndEinAbBezirk(UUID id, Bezirk bezirk);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    List<Slot> findAllByEinAbInOrderByEinAbStartDateTimeAsc(Collection<EinAb> einAbs);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where s.status in :statuses
          and s.einAb.bezirk = :bezirk
        order by s.einAb.startDateTime asc
        """)
    Page<Slot> findAllByStatusInAndBezirkOrderByEinAbStartDateTimeAsc(@Param("statuses") Collection<SlotStatus> statuses,
                                                                     @Param("bezirk") Bezirk bezirk,
                                                                     Pageable pageable);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where s.bookingUser in :users
          and s.status in :statuses
          and s.bookingUser.active = true
          and s.einAb.bezirk = :bezirk
        order by s.einAb.startDateTime desc
        """)
    List<Slot> findAllByActiveBookingUsersAndStatusesAndBezirk(@Param("users") Collection<User> users,
                                                               @Param("statuses") Collection<SlotStatus> statuses,
                                                               @Param("bezirk") Bezirk bezirk);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where s.bookingUser in :users
          and s.status in :statuses
          and s.bookingUser.active = true
        order by s.einAb.startDateTime desc
        """)
    List<Slot> findAllByActiveBookingUsersAndStatuses(@Param("users") Collection<User> users,
                                                      @Param("statuses") Collection<SlotStatus> statuses);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where s.bookingUser = :bookingUser
          and s.status in :statuses
          and s.einAb.bezirk = :bezirk
        order by s.einAb.startDateTime asc
        """)
    Page<Slot> findAllByBookingUserAndStatusesAndBezirk(@Param("bookingUser") User bookingUser,
                                                        @Param("statuses") Collection<SlotStatus> statuses,
                                                        @Param("bezirk") Bezirk bezirk,
                                                        Pageable pageable);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where (s.einAb.teacher = :teacher or s.teacher = :teacher)
          and s.einAb.bezirk = :bezirk
          and s.status in :statuses
        order by s.einAb.startDateTime desc
        """)
    Page<Slot> findAllByTeacherAndStatusesAndBezirk(@Param("teacher") User teacher,
                                                    @Param("statuses") Collection<SlotStatus> statuses,
                                                    @Param("bezirk") Bezirk bezirk,
                                                    Pageable pageable);

    @Query("""
        select count(s) from Slot s
        where s.bookingUser = :bookingUser
          and s.status = :status
          and s.einAb.bezirk = :bezirk
          and s.einAb.category not member of s.einAb.bezirk.ignoredCategories
        """)
    long countByBookingUserAndStatusAndEinAbBezirkExcludingIgnoredCategories(
            @Param("bookingUser") User bookingUser,
            @Param("status") SlotStatus status,
            @Param("bezirk") Bezirk bezirk);

    /**
     * Counts the user's EinAbs over all Bezirke, past and upcoming, for the booking limit. A user has one Bezirk,
     * but one moved by an admin keeps the EinAbs of the old Bezirk, so they still count. Each EinAb's own Bezirk
     * decides whether its category is ignored.
     */
    @Query("""
        select count(s) from Slot s
        where s.bookingUser = :bookingUser
          and s.status in :statuses
          and s.einAb.category not member of s.einAb.bezirk.ignoredCategories
        """)
    long countByBookingUserAndStatusInExcludingIgnoredCategories(
            @Param("bookingUser") User bookingUser,
            @Param("statuses") Collection<SlotStatus> statuses);

    boolean existsByBookingUserAndStatusInAndEinAbVisitFairteilerTrue(
            User bookingUser,
            Collection<SlotStatus> statuses);

    @Query("""
        select (count(s) > 0) from Slot s
        where s.bookingUser = :bookingUser
          and s.status in :statuses
          and s.teacher = :teacher
          and s.einAb.bezirk = :bezirk
          and s.einAb.category not member of s.einAb.bezirk.ignoredCategories
        """)
    boolean existsByBookingUserAndStatusInAndTeacherAndEinAbBezirkExcludingIgnoredCategories(
            @Param("bookingUser") User bookingUser,
            @Param("statuses") Collection<SlotStatus> statuses,
            @Param("teacher") User teacher,
            @Param("bezirk") Bezirk bezirk);

    boolean existsByBookingUserAndStatusInAndEinAb(User bookingUser,
                                                   Collection<SlotStatus> statuses,
                                                   EinAb einAb);

    boolean existsByBookingUserAndStatusInAndEinAbCategoryAndEinAbBezirk(User bookingUser,
                                                                         Collection<SlotStatus> statuses,
                                                                         EinAbCategory category,
                                                                         Bezirk bezirk);

    boolean existsByEinAbAndStatusIn(EinAb einAb, Collection<SlotStatus> statuses);

    boolean existsByBookingUserAndStatusInAndTeacherAndEinAbStartDateTime(User bookingUser,
                                                                           Collection<SlotStatus> statuses,
                                                                           User teacher,
                                                                           java.time.Instant startDateTime);

    @Query("""
        select distinct s.einAb.id from Slot s
        where s.einAb in :einAbs
          and s.status in :statuses
        """)
    List<UUID> findEinAbIdsWithSlotsInStatuses(@Param("einAbs") Collection<EinAb> einAbs,
                                               @Param("statuses") Collection<SlotStatus> statuses);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    List<Slot> findAllByEinAbOrderByCreatedAtAsc(EinAb einAb);

    @EntityGraph(attributePaths = {"einAb", "bookingUser"})
    List<Slot> findAllByEinAbInAndStatusInOrderByBookedAtAsc(Collection<EinAb> einAbs, Collection<SlotStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.einAb = :einAb")
    List<Slot> findAllForUpdateByEinAb(@Param("einAb") EinAb einAb);

    @EntityGraph(attributePaths = {"einAb", "einAb.bezirk", "einAb.teacher", "einAb.teacher.bezirk", "teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk"})
    @Query("""
        select s from Slot s
        where s.einAb.id = :einAbId
          and s.status in :statuses
          and s.bookingUser.active = true
        order by s.createdAt asc
        """)
    List<Slot> findAllByEinAbIdAndStatusIn(@Param("einAbId") UUID einAbId,
                                           @Param("statuses") Collection<SlotStatus> statuses);

    @EntityGraph(attributePaths = {"einAb"})
    List<Slot> findAllByEinAbBezirkAndEinAbStartDateTimeBetween(Bezirk bezirk, java.time.Instant from, java.time.Instant to);

    long countByTeacherAndStatusAndEinAbStartDateTimeAfter(User teacher, SlotStatus status, java.time.Instant after);
}
