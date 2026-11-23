package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.AccountDeletionToken;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.AccountDeletionTokenRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a user's account, as the App Store and Google Play require: from the profile page while signed in, or
 * from the public deletion page after confirming a link sent as Foodsharing message.
 *
 * <p>Personal data is removed (name, phone, email, calendar link, sessions, phones for push, notification
 * subscription, the EinAb-Gebenden's notes about the person) and future bookings are released. Past bookings stay as
 * anonymous rows so the EinAb history and statistics of the EinAb-Gebenden remain correct. Signing in again later
 * creates a new, empty account.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    static final String DELETED_NAME = "Gelöschtes Konto";
    private static final String DELETED_ID_PREFIX = "deleted-";
    private static final Duration LINK_VALIDITY = Duration.ofHours(24);
    private static final int MAX_LINKS_PER_ACCOUNT_PER_HOUR = 3;

    private final UserRepository userRepository;
    private final AccountDeletionTokenRepository tokenRepository;
    private final TokenService tokenService;
    private final FoodsharingMessageService messageService;
    private final MessageTemplateService messageTemplateService;
    private final AppProperties appProperties;
    private final LoginRateLimiter loginRateLimiter;
    private final EntityManager entityManager;

    public AccountDeletionService(UserRepository userRepository,
                                  AccountDeletionTokenRepository tokenRepository,
                                  TokenService tokenService,
                                  FoodsharingMessageService messageService,
                                  MessageTemplateService messageTemplateService,
                                  AppProperties appProperties,
                                  LoginRateLimiter loginRateLimiter,
                                  EntityManager entityManager) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.tokenService = tokenService;
        this.messageService = messageService;
        this.messageTemplateService = messageTemplateService;
        this.appProperties = appProperties;
        this.loginRateLimiter = loginRateLimiter;
        this.entityManager = entityManager;
    }

    /**
     * Sends a confirmation link to the Foodsharing inbox of the account. Answers the same whether or not the account
     * exists, so the page cannot be used to find out who uses the tool.
     */
    @Transactional
    public void requestDeletion(String foodsharingId, String clientIp) {
        String normalizedId = foodsharingId == null ? "" : foodsharingId.trim();
        if (normalizedId.isEmpty() || normalizedId.startsWith(DELETED_ID_PREFIX)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("foodsharingId"));
        }
        loginRateLimiter.check(normalizedId, clientIp);
        if (tokenRepository.countByFoodsharingIdIgnoreCaseAndCreatedAtAfter(normalizedId, Instant.now().minus(Duration.ofHours(1)))
                >= MAX_LINKS_PER_ACCOUNT_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.TOO_MANY_LOGIN_REQUESTS);
        }
        User user = userRepository.findByFoodsharingIdIgnoreCase(normalizedId).orElse(null);
        if (user == null) {
            return;
        }
        String rawToken = tokenService.generateToken();
        AccountDeletionToken token = new AccountDeletionToken();
        token.setFoodsharingId(user.getFoodsharingId());
        token.setTokenHash(tokenService.hash(rawToken));
        token.setExpiresAt(Instant.now().plus(LINK_VALIDITY));
        tokenRepository.save(token);

        String link = appProperties.getFrontend().getBaseUrl() + "/konto-loeschen?token=" + rawToken;
        LanguageCode language = user.getPreferredLanguage() == null ? LanguageCode.DE : user.getPreferredLanguage();
        // No push copy: the link deletes the account and should not show on the lock screen.
        messageService.sendWithoutPush(
                user.getFoodsharingId(),
                messageTemplateService.accountDeletionSubject(language),
                messageTemplateService.accountDeletionBody(language, link));
    }

    /** Confirms a link from {@link #requestDeletion} and deletes the account. */
    @Transactional
    public void confirmDeletion(String rawToken) {
        AccountDeletionToken token = rawToken == null ? null
                : tokenRepository.findByTokenHash(tokenService.hash(rawToken.trim())).orElse(null);
        if (token == null || token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.ACCOUNT_DELETION_LINK_INVALID);
        }
        token.setUsedAt(Instant.now());
        User user = userRepository.findByFoodsharingIdIgnoreCase(token.getFoodsharingId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.ACCOUNT_DELETION_LINK_INVALID));
        delete(user);
    }

    /** Deletes the account of the signed-in user (profile page). */
    @Transactional
    public void deleteOwnAccount(String foodsharingId) {
        User user = userRepository.findByFoodsharingIdIgnoreCase(foodsharingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.ACCOUNT_NOT_FOUND));
        delete(user);
    }

    private void delete(User user) {
        Instant now = Instant.now();
        ensureDeletable(user, now);
        String oldId = user.getFoodsharingId();
        String newId = DELETED_ID_PREFIX + user.getId();

        releaseFutureBookings(user, now);

        // Notes of the EinAb-Gebenden about this person, and everything only needed to reach or sign in the person.
        execute("delete from BookingComment c where c.bookingUser = :user", "user", user);
        execute("delete from NotificationSubscription n where n.user = :user", "user", user);
        execute("delete from UserRefreshJob j where j.user = :user", "user", user);
        executeById("delete from PushDevice d where lower(d.foodsharingId) = lower(:id)", oldId);
        executeById("delete from AuthSession s where lower(s.foodsharingId) = lower(:id)", oldId);
        executeById("delete from LoginToken t where lower(t.foodsharingId) = lower(:id)", oldId);
        executeById("delete from AccountDeletionToken t where lower(t.foodsharingId) = lower(:id) and t.usedAt is null", oldId);
        executeById("delete from SurveyCoauthor c where lower(c.foodsharingId) = lower(:id)", oldId);

        // Kept for the admins and survey owners, but no longer linked to the person.
        entityManager.createQuery("update SurveyResponseEntry r set r.foodsharingId = :newId, r.respondentName = :name"
                        + " where lower(r.foodsharingId) = lower(:id)")
                .setParameter("newId", newId).setParameter("name", DELETED_NAME).setParameter("id", oldId)
                .executeUpdate();
        entityManager.createQuery("update Survey s set s.ownerFoodsharingId = :newId where lower(s.ownerFoodsharingId) = lower(:id)")
                .setParameter("newId", newId).setParameter("id", oldId)
                .executeUpdate();
        entityManager.createQuery("update SupportRequest r set r.contactFoodsharingId = :newId, r.contactName = :name,"
                        + " r.userAgent = null where r.user = :user or lower(r.contactFoodsharingId) = lower(:id)")
                .setParameter("newId", newId).setParameter("name", DELETED_NAME)
                .setParameter("user", user).setParameter("id", oldId)
                .executeUpdate();

        user.setFoodsharingId(newId);
        user.setName(DELETED_NAME);
        user.setEmail(null);
        user.setPhoneNumber(null);
        user.setIcalLink(null);
        user.setIcalFeedToken(null);
        user.setActive(false);
        user.setPrivacyConsentAt(null);
        user.setPrivacyConsentVersion(null);
        user.setBezirkInfoSeenBezirkId(null);
        user.setBezirkInfoSeenAt(null);
        user.setVerified(false);
        user.setLastVerifiedAt(null);
        user.setLastLoginAt(null);
        user.setVerificationMessageSentAt(null);
        user.setCanGiveEinAbs(false);
        user.setCanManageUsers(false);
        user.setCanUseAutomations(false);
        user.setCanSeeUserPickupCountGrouping(false);
        user.setCanSeeAllAutomationDecisions(false);
        user.setCanManageSurveys(false);
        log.info("Account {} deleted (now {})", user.getId(), newId);
    }

    /** EinAb-Gebende first hand over or delete their future EinAbs; automation users first disconnect Foodsharing. */
    private void ensureDeletable(User user, Instant now) {
        Long futureEinAbs = entityManager.createQuery(
                        "select count(e) from EinAb e where e.teacher = :user and e.startDateTime > :now", Long.class)
                .setParameter("user", user).setParameter("now", now)
                .getSingleResult();
        if (futureEinAbs > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ACCOUNT_DELETION_FUTURE_EINABS);
        }
        Long connections = entityManager.createQuery(
                        "select count(c) from FoodsharingAdminConnection c where c.adminUser = :user", Long.class)
                .setParameter("user", user)
                .getSingleResult();
        if (connections > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ACCOUNT_DELETION_FOODSHARING_CONNECTED);
        }
    }

    private void releaseFutureBookings(User user, Instant now) {
        List<Slot> futureBookings = entityManager.createQuery(
                        "select s from Slot s join fetch s.einAb e join fetch s.teacher"
                                + " where s.bookingUser = :user and s.status = :status and e.startDateTime > :now", Slot.class)
                .setParameter("user", user).setParameter("status", SlotStatus.BOOKED).setParameter("now", now)
                .getResultList();
        for (Slot slot : futureBookings) {
            User teacher = slot.getTeacher();
            LanguageCode language = teacher.getPreferredLanguage() == null ? LanguageCode.DE : teacher.getPreferredLanguage();
            // Same message as when the person cancels the booking; the name is still there at this point.
            messageService.send(
                    teacher.getFoodsharingId(),
                    messageTemplateService.teacherBookingCancellationSubject(language),
                    messageTemplateService.teacherBookingCancellationBody(language, slot));
            slot.setStatus(SlotStatus.AVAILABLE);
            slot.setBookingUser(null);
            slot.setBookedAt(null);
            slot.setDoneAt(null);
        }
        entityManager.flush();
    }

    private void execute(String jpql, String name, Object value) {
        entityManager.createQuery(jpql).setParameter(name, value).executeUpdate();
    }

    private void executeById(String jpql, String foodsharingId) {
        entityManager.createQuery(jpql).setParameter("id", foodsharingId).executeUpdate();
    }
}
