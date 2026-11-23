package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import jakarta.persistence.LockModeType;
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

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = {"bezirk"})
    Optional<User> findByFoodsharingIdIgnoreCaseAndActiveTrue(String foodsharingId);

    @EntityGraph(attributePaths = {"bezirk"})
    Optional<User> findByFoodsharingIdIgnoreCase(String foodsharingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    Optional<User> findByIcalFeedToken(String icalFeedToken);

    boolean existsByIcalFeedToken(String icalFeedToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where lower(u.foodsharingId) = lower(:foodsharingId)")
    Optional<User> findByFoodsharingIdIgnoreCaseForUpdate(@Param("foodsharingId") String foodsharingId);

    @EntityGraph(attributePaths = {"bezirk"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithBezirkById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"bezirk"})
    List<User> findAllByBezirk(Bezirk bezirk);

    @EntityGraph(attributePaths = {"bezirk"})
    List<User> findAllByBezirkIsNull();

    /** People without a Bezirk, or whose Bezirk was disabled: both have to choose (or be given) a new one. */
    @EntityGraph(attributePaths = {"bezirk"})
    @Query("select u from User u left join u.bezirk b where b is null or b.active = false")
    List<User> findAllWithoutActiveBezirk();

    @EntityGraph(attributePaths = {"bezirk"})
    @Query("select u from User u")
    List<User> findAllWithBezirk();

    List<User> findAllByCanManageSurveysTrueAndActiveTrueOrderByNameAsc();

    List<User> findAllByCanManageUsersTrueAndActiveTrueAndBezirk(Bezirk bezirk);

    List<User> findAllByCanManageUsersTrueAndActiveTrue();

    @EntityGraph(attributePaths = {"bezirk"})
    Page<User> findAllByCanManageUsersTrueOrderByNameAsc(Pageable pageable);

    @EntityGraph(attributePaths = {"bezirk"})
    @Query("""
        select u from User u
        where u.bezirk = :bezirk
          and u.canGiveEinAbs = true
        order by u.name asc
        """)
    Page<User> findAllTeachersByBezirk(@Param("bezirk") Bezirk bezirk, Pageable pageable);

    @EntityGraph(attributePaths = {"bezirk"})
    @Query("""
        select u from User u
        where u.bezirk = :bezirk
          and u.active = true
          and u.canGiveEinAbs = true
        order by u.name asc
        """)
    List<User> findAllAssignableTeachersByBezirk(@Param("bezirk") Bezirk bezirk);

    /** Admin person search: people of the Bezirk or without a Bezirk, active or not, whose name contains or Foodsharing ID starts with the text. */
    @Query("""
        select u from User u left join u.bezirk b
        where (b = :bezirk or b is null)
          and (lower(u.name) like :namePattern escape '\\' or lower(u.foodsharingId) like :idPattern escape '\\')
        order by u.name asc
        """)
    List<User> searchByBezirkOrWithoutBezirk(@Param("bezirk") Bezirk bezirk,
                                             @Param("namePattern") String namePattern,
                                             @Param("idPattern") String idPattern,
                                             Pageable pageable);

    /** People of a Bezirk whose name contains, or whose Foodsharing ID starts with, the search text. */
    @Query("""
        select u from User u
        where u.bezirk = :bezirk
          and u.active = true
          and u.id <> :excludedId
          and (lower(u.name) like :namePattern escape '\\' or lower(u.foodsharingId) like :idPattern escape '\\')
        order by u.name asc
        """)
    List<User> searchActiveByBezirk(@Param("bezirk") Bezirk bezirk,
                                    @Param("excludedId") UUID excludedId,
                                    @Param("namePattern") String namePattern,
                                    @Param("idPattern") String idPattern,
                                    Pageable pageable);


    /** Highest Foodsharing ID in a range of equally long numeric IDs (used for the generated demo accounts). */
    @Query("select max(u.foodsharingId) from User u where u.foodsharingId >= :from and u.foodsharingId <= :to")
    Optional<String> findMaxFoodsharingIdBetween(@Param("from") String from, @Param("to") String to);
}
