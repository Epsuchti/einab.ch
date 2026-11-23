package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingMetricDailyCount;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingMetricDailyCountRepository extends JpaRepository<FoodsharingMetricDailyCount, UUID> {
    Optional<FoodsharingMetricDailyCount> findByBezirkIdAndMetricDayAndMetricKey(UUID bezirkId, LocalDate metricDay, String metricKey);
    List<FoodsharingMetricDailyCount> findAllByBezirkIdAndMetricDayBetween(UUID bezirkId, LocalDate from, LocalDate to);
}
