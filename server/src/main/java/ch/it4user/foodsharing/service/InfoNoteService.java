package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.InfoNote;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.InfoNoteSeverity;
import ch.it4user.foodsharing.repository.BezirkRepository;
import ch.it4user.foodsharing.repository.InfoNoteRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Info notes: banners admins add for the whole site (updates, maintenance) or for one Bezirk. Everyone sees the
 * notes running right now when they open the page; dismissing is remembered in the browser only.
 */
@Service
public class InfoNoteService {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final InfoNoteRepository infoNoteRepository;
    private final BezirkRepository bezirkRepository;

    public InfoNoteService(InfoNoteRepository infoNoteRepository, BezirkRepository bezirkRepository) {
        this.infoNoteRepository = infoNoteRepository;
        this.bezirkRepository = bezirkRepository;
    }

    /** What an admin entered; {@code bezirkSlug} null or blank means the whole site. */
    public record InfoNoteInput(String bezirkSlug, String title, String message, InfoNoteSeverity severity,
                                Instant startsAt, Instant endsAt) {
    }

    /**
     * Notes running now for the whole site and, when {@code bezirkSlug} names an active Bezirk, for that Bezirk.
     * An unknown or disabled Bezirk only gets the site-wide notes.
     */
    @Transactional(readOnly = true)
    public List<InfoNote> findActive(String bezirkSlug) {
        Instant now = Instant.now();
        if (bezirkSlug == null || bezirkSlug.isBlank()) {
            return infoNoteRepository.findActiveSiteWide(now);
        }
        return bezirkRepository.findBySlugAndActiveTrue(bezirkSlug.trim().toLowerCase(Locale.ROOT))
                .map(bezirk -> infoNoteRepository.findActiveForBezirk(now, bezirk))
                .orElseGet(() -> infoNoteRepository.findActiveSiteWide(now));
    }

    @Transactional(readOnly = true)
    public List<InfoNote> findAllForAdmin() {
        return infoNoteRepository.findAllByOrderByEndsAtDescStartsAtDesc();
    }

    @Transactional
    public InfoNote create(InfoNoteInput input, User admin) {
        InfoNote note = new InfoNote();
        apply(note, input);
        note.setCreatedByName(admin.getName());
        return infoNoteRepository.save(note);
    }

    @Transactional
    public InfoNote update(UUID id, InfoNoteInput input) {
        InfoNote note = require(id);
        apply(note, input);
        return infoNoteRepository.save(note);
    }

    @Transactional
    public void delete(UUID id) {
        infoNoteRepository.delete(require(id));
    }

    private InfoNote require(UUID id) {
        return infoNoteRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND));
    }

    private void apply(InfoNote note, InfoNoteInput input) {
        String title = input.title() == null ? "" : input.title().trim();
        String message = input.message() == null ? "" : input.message().trim();
        List<String> problems = new ArrayList<>();
        if (title.length() > MAX_TITLE_LENGTH) {
            problems.add("Title may have at most " + MAX_TITLE_LENGTH + " characters.");
        }
        if (message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH) {
            problems.add("Message must be between 1 and " + MAX_MESSAGE_LENGTH + " characters.");
        }
        if (input.startsAt() == null || input.endsAt() == null) {
            problems.add("Start and end are required.");
        } else if (!input.endsAt().isAfter(input.startsAt())) {
            problems.add("End must be after the start.");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, problems);
        }
        Bezirk bezirk = null;
        if (input.bezirkSlug() != null && !input.bezirkSlug().isBlank()) {
            bezirk = bezirkRepository.findBySlug(input.bezirkSlug().trim().toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND));
        }
        note.setBezirk(bezirk);
        note.setTitle(title.isEmpty() ? null : title);
        note.setMessage(message);
        note.setSeverity(input.severity() == null ? InfoNoteSeverity.INFO : input.severity());
        note.setStartsAt(input.startsAt());
        note.setEndsAt(input.endsAt());
    }
}
