package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Anonymous daily total of one metric for one Bezirk, kept after the underlying audits are deleted. */
@Entity
@Table(name = "foodsharing_metric_daily_counts", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_metric_daily_counts",
        columnNames = {"bezirk_id", "metric_day", "metric_key"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingMetricDailyCount extends BaseEntity {
    @Column(name = "bezirk_id", nullable = false)
    private UUID bezirkId;

    @Column(name = "metric_day", nullable = false)
    private LocalDate metricDay;

    @Column(name = "metric_key", nullable = false, length = 100)
    private String metricKey;

    @Column(name = "metric_value", nullable = false)
    private long metricValue;
}
