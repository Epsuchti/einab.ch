package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.Slot;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lookups the past EinAb import needs to recognise rows that were imported (or entered by hand) before. */
public interface PastEinAbImportRepository extends JpaRepository<Slot, UUID> {

    @Query("""
        select s from Slot s join fetch s.einAb e
        where s.bookingUser.id in :userIds
          and e.bezirk = :bezirk
          and e.startDateTime >= :from
          and e.startDateTime < :to
          and s.status in (
            ch.it4user.foodsharing.domain.enumtype.SlotStatus.DONE,
            ch.it4user.foodsharing.domain.enumtype.SlotStatus.BOOKED,
            ch.it4user.foodsharing.domain.enumtype.SlotStatus.CANCELLED)
        """)
    List<Slot> findBookedSlots(@Param("userIds") Collection<UUID> userIds,
                               @Param("bezirk") Bezirk bezirk,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    @Query("select distinct e.businessName from EinAb e where e.bezirk = :bezirk and e.businessName is not null")
    List<String> findBusinessNames(@Param("bezirk") Bezirk bezirk);
}
