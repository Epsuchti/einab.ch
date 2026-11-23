package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.PrivateInfoTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivateInfoTemplateRepository extends JpaRepository<PrivateInfoTemplate, UUID> {

    List<PrivateInfoTemplate> findAllByTeacherIdOrderByStoreKeyAsc(UUID teacherId);

    Optional<PrivateInfoTemplate> findByTeacherIdAndStoreKey(UUID teacherId, String storeKey);
}
