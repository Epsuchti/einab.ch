package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;

public interface BezirkRepository extends JpaRepository<Bezirk, UUID> {

    Optional<Bezirk> findBySlugAndActiveTrue(String slug);

    @EntityGraph(attributePaths = {"ignoredCategories", "disabledCategories"})
    Optional<Bezirk> findWithIgnoredCategoriesBySlugAndActiveTrue(String slug);

    List<Bezirk> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    @EntityGraph(attributePaths = "disabledCategories")
    List<Bezirk> findWithDisabledCategoriesByActiveTrueOrderBySortOrderAscNameAsc();

    Optional<Bezirk> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Bezirk> findAllByOrderByActiveDescSortOrderAscNameAsc();

    long countByActiveTrue();

    Optional<Bezirk> findFirstByOrderBySortOrderDesc();

    boolean existsByCleaningStoreIdAndIdNot(Long cleaningStoreId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Bezirk> findFirstByOrderBySortOrderAscIdAsc();
}
