package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One-time link, sent as a Foodsharing message, that confirms deleting an account from the public deletion page. */
@Entity
@Table(name = "account_deletion_tokens")
@Getter
@Setter
@NoArgsConstructor
public class AccountDeletionToken extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String foodsharingId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant usedAt;
}
