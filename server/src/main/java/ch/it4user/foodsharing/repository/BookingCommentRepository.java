package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.BookingComment;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingCommentRepository extends JpaRepository<BookingComment, UUID> {

    @EntityGraph(attributePaths = {"teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk", "slot"})
    List<BookingComment> findAllByBookingUserOrderByCreatedAtAsc(User bookingUser);

    @EntityGraph(attributePaths = {"teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk", "slot"})
    List<BookingComment> findAllByBookingUserInOrderByCreatedAtAsc(Collection<User> bookingUsers);

    @EntityGraph(attributePaths = {"teacher", "teacher.bezirk", "bookingUser", "bookingUser.bezirk", "slot"})
    List<BookingComment> findAllBySlotAndBookingUserOrderByCreatedAtAsc(Slot slot, User bookingUser);

    /** Keeps comments on the person when their slot is deleted together with its EinAb. */
    @Modifying
    @Query("update BookingComment c set c.slot = null where c.slot in :slots")
    int detachFromSlots(@Param("slots") Collection<Slot> slots);
}
