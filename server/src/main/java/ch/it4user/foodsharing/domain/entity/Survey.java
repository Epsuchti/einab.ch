package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A survey owned (and only readable in its results) by the Foodsharing user who created it. */
@Entity
@Table(name = "surveys")
@Getter
@Setter
@NoArgsConstructor
public class Survey extends BaseEntity {

    @Column(name = "owner_foodsharing_id", nullable = false, length = 100)
    private String ownerFoodsharingId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description = "";
}
