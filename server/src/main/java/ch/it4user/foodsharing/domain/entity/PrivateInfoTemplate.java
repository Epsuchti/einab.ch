package ch.it4user.foodsharing.domain.entity;

import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Locale;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A teacher's saved "Infos für Teilnehmende". There is one template per store (keyed by the normalized store name).
 * Category templates (store name null, key "category:CATEGORY") are kept for online EinAbs, which have no store,
 * and as the fallback for stores that have no template of their own yet.
 */
@Entity
@Table(name = "private_info_templates", uniqueConstraints = @UniqueConstraint(
        name = "uk_private_info_template_teacher_store_key",
        columnNames = {"teacher_id", "store_key"}))
@Getter
@Setter
@NoArgsConstructor
public class PrivateInfoTemplate extends BaseEntity {

    private static final String CATEGORY_KEY_PREFIX = "category:";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    /** For a store template, the category of the EinAb it was last saved from. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EinAbCategory category;

    /** The store name as the teacher last typed it; null for a category template. */
    @Column(length = 500)
    private String storeName;

    @Column(nullable = false, length = 500)
    private String storeKey;

    @Column(nullable = false, length = 4000)
    private String content;

    public boolean isCategoryTemplate() {
        return storeName == null;
    }

    /** Lower case letters and digits only, so "Migros  Höngg" and "migros-höngg" share one template. */
    public static String storeKeyFor(String storeName) {
        if (storeName == null) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        storeName.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(key::appendCodePoint);
        return key.toString();
    }

    public static String categoryKeyFor(EinAbCategory category) {
        return CATEGORY_KEY_PREFIX + category.name();
    }
}
