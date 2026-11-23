package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.PushDevice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {

    Optional<PushDevice> findByToken(String token);

    List<PushDevice> findAllByFoodsharingId(String foodsharingId);

    @Modifying
    @Query("delete from PushDevice d where d.token = :token")
    int deleteByToken(@Param("token") String token);

    @Modifying
    @Query("delete from PushDevice d where d.lastSeenAt < :before")
    int deleteByLastSeenAtBefore(@Param("before") Instant before);
}
