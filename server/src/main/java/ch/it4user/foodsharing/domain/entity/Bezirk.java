package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.HashSet;
import java.util.Set;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bezirke")
@Getter
@Setter
@NoArgsConstructor
public class Bezirk extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100, updatable = false)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "cleaning_store_id", unique = true)
    private Long cleaningStoreId;

    @Column(nullable = false)
    private boolean preventDuplicateTeacherBookings = true;

    @Column(nullable = false)
    private boolean preventDuplicateCategoryBookings = true;

    @Column(nullable = false)
    private int maxActiveBookingsPerUser = 3;
    /** EinAbs created from a teacher's pickup start this many minutes before the pickup. */
    @Column(name = "pickup_einab_lead_minutes", nullable = false)
    private int pickupEinAbLeadMinutes = 10;

    @Column(name = "REQUIRE_FAIRTEILER_VISIT_BY_THIRD_EINAB", nullable = false)
    private boolean requireFairteilerVisitByThirdEinAb = false;

    @Column(name = "use_foodsharing_user_info_api", nullable = false)
    private boolean useFoodsharingUserInfoApi = false;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    /** Show the info page below as second onboarding step, after the privacy consent. */
    @Column(name = "info_step_enabled", nullable = false)
    private boolean infoStepEnabled = false;

    /** Show the info step only to users who are not verified on Foodsharing yet. */
    @Column(name = "info_step_only_unverified", nullable = false)
    private boolean infoStepOnlyUnverified = false;

    /**
     * The Bezirk's info page in Markdown, German; also the fallback for a language without its own text. Also
     * reachable later from the help page and the menu.
     */
    @Column(name = "info_markdown", length = 1000000)
    private String infoMarkdown;

    @Column(name = "info_markdown_en", length = 1000000)
    private String infoMarkdownEn;

    @Column(name = "info_markdown_gws", length = 1000000)
    private String infoMarkdownGws;

    public boolean hasInfo() {
        return !infoMarkdown(LanguageCode.DE).isEmpty();
    }

    /** The text in the given language; German, then any filled language, when that one is empty. "" when there is none. */
    public String infoMarkdown(LanguageCode language) {
        String own = switch (language == null ? LanguageCode.DE : language) {
            case EN -> infoMarkdownEn;
            case GWS -> infoMarkdownGws;
            case DE -> infoMarkdown;
        };
        for (String candidate : new String[] {own, infoMarkdown, infoMarkdownGws, infoMarkdownEn}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    @ElementCollection
    @CollectionTable(name = "bezirk_ignored_categories", joinColumns = @JoinColumn(name = "bezirk_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 64)
    private Set<EinAbCategory> ignoredCategories = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "bezirk_disabled_categories", joinColumns = @JoinColumn(name = "bezirk_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 64)
    private Set<EinAbCategory> disabledCategories = new HashSet<>();
}
