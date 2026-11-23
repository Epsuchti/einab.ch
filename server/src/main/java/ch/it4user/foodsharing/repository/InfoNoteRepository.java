package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.InfoNote;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InfoNoteRepository extends JpaRepository<InfoNote, UUID> {

    /** Notes for the whole site running at {@code now}. */
    @Query("""
            select n from InfoNote n
            where n.bezirk is null and n.startsAt <= :now and n.endsAt > :now
            order by n.startsAt desc
            """)
    List<InfoNote> findActiveSiteWide(@Param("now") Instant now);

    /** Notes for the whole site and for the given Bezirk running at {@code now}. */
    @Query("""
            select n from InfoNote n
            where (n.bezirk is null or n.bezirk = :bezirk) and n.startsAt <= :now and n.endsAt > :now
            order by n.startsAt desc
            """)
    List<InfoNote> findActiveForBezirk(@Param("now") Instant now, @Param("bezirk") Bezirk bezirk);

    @EntityGraph(attributePaths = "bezirk")
    List<InfoNote> findAllByOrderByEndsAtDescStartsAtDesc();
}
