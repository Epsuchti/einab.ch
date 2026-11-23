package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.repository.BezirkRepository;
import ch.it4user.foodsharing.repository.NotificationSubscriptionRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingUserService {

    private final UserRepository bookingUserRepository;
    private final BezirkRepository bezirkRepository;
    private final FoodsharingClient foodsharingClient;
    private final NotificationSubscriptionRepository notificationSubscriptionRepository;

    public BookingUserService(UserRepository bookingUserRepository,
                              BezirkRepository bezirkRepository,
                              FoodsharingClient foodsharingClient,
                              NotificationSubscriptionRepository notificationSubscriptionRepository) {
        this.bookingUserRepository = bookingUserRepository;
        this.bezirkRepository = bezirkRepository;
        this.foodsharingClient = foodsharingClient;
        this.notificationSubscriptionRepository = notificationSubscriptionRepository;
    }

    @Transactional
    public User getOrCreate(String foodsharingId, LanguageCode language, Bezirk selectedBezirk) {
        String normalizedFoodsharingId = normalizeFoodsharingId(foodsharingId);
        Bezirk userBezirk = bookingUserRepository.findByFoodsharingIdIgnoreCase(normalizedFoodsharingId)
                .map(User::getBezirk)
                .orElse(selectedBezirk);
        FoodsharingUserInfo foodsharingUser = foodsharingClient.getUser(
                normalizedFoodsharingId,
                userBezirk);
        User bookingUser = bookingUserRepository.findByFoodsharingIdIgnoreCaseForUpdate(normalizedFoodsharingId)
                .orElseGet(() -> createUserWithIdentityLock(normalizedFoodsharingId));
        // Generated demo accounts keep the name they got at creation (Foodsharing is never asked for them).
        if (foodsharingUser.name() != null) {
            bookingUser.setName(foodsharingUser.name());
        }
        if (foodsharingUser.phoneNumber() != null) {
            bookingUser.setPhoneNumber(foodsharingUser.phoneNumber());
        }
        applyVerification(bookingUser, foodsharingUser);
        bookingUser.setPreferredLanguage(language);
        return bookingUser;
    }

    /**
     * Finds or creates the user without asking Foodsharing (used by bulk imports, which refresh the user data slowly
     * in the background afterwards). A new user is named after its Foodsharing ID until that refresh ran.
     */
    @Transactional
    public User getOrCreateWithoutLookup(String foodsharingId, Bezirk bezirk) {
        String normalizedFoodsharingId = normalizeFoodsharingId(foodsharingId);
        User bookingUser = bookingUserRepository.findByFoodsharingIdIgnoreCaseForUpdate(normalizedFoodsharingId)
                .orElseGet(() -> createUserWithIdentityLock(normalizedFoodsharingId));
        assignToBezirk(bookingUser, bezirk);
        return bookingUser;
    }

    public void assignToBezirk(User bookingUser, Bezirk bezirk) {
        // People of a disabled Bezirk move to the Bezirk they use next (login, booking, assignment by an admin).
        if (bookingUser.getBezirk() == null || !bookingUser.getBezirk().isActive()) {
            bookingUser.setBezirk(bezirk);
            return;
        }
        if (!bookingUser.getBezirk().getId().equals(bezirk.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_BEZIRK_MISMATCH);
        }
    }

    public User getByFoodsharingId(String foodsharingId) {
        return bookingUserRepository.findByFoodsharingIdIgnoreCaseAndActiveTrue(normalizeFoodsharingId(foodsharingId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
    }

    @Transactional
    public User refreshUserData(UUID bookingUserId) {
        User bookingUser = bookingUserRepository.findWithBezirkById(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        FoodsharingUserInfo foodsharingUser = foodsharingClient.getUser(
                bookingUser.getFoodsharingId(), bookingUser.getBezirk());
        // Generated demo accounts keep the name they got at creation (Foodsharing is never asked for them).
        if (foodsharingUser.name() != null) {
            bookingUser.setName(foodsharingUser.name());
        }
        if (foodsharingUser.phoneNumber() != null) {
            bookingUser.setPhoneNumber(foodsharingUser.phoneNumber());
        }
        applyVerification(bookingUser, foodsharingUser);
        return bookingUser;
    }

    @Transactional
    public User verify(UUID bookingUserId) {
        User bookingUser = bookingUserRepository.findWithBezirkById(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        bookingUser.setVerified(true);
        notificationSubscriptionRepository.deactivateByUser(bookingUser);
        return bookingUser;
    }

    /**
     * Removes the verification manually. A later sync with Foodsharing that reports the user as verified sets it again.
     */
    @Transactional
    public User unverify(UUID bookingUserId) {
        User bookingUser = bookingUserRepository.findWithBezirkById(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        bookingUser.setVerified(false);
        return bookingUser;
    }

    /** Marks (or unmarks) that an admin sent the user a message about the verification. */
    @Transactional
    public User setVerificationMessageSent(UUID bookingUserId, boolean sent) {
        User bookingUser = bookingUserRepository.findWithBezirkById(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        if (!sent) {
            bookingUser.setVerificationMessageSentAt(null);
        } else if (bookingUser.getVerificationMessageSentAt() == null) {
            bookingUser.setVerificationMessageSentAt(Instant.now());
        }
        return bookingUser;
    }

    /**
     * Takes over the verification state reported by Foodsharing. An unknown state leaves the flag untouched, so a
     * manual verification is only reset when Foodsharing reports that the verification was revoked. A user who is
     * verified afterwards no longer needs the notifications for new EinAbs, so those are switched off.
     */
    public void applyVerification(User user, FoodsharingUserInfo userInfo) {
        if (userInfo == null) {
            return;
        }
        if (userInfo.verified() != null) {
            user.setVerified(userInfo.verified());
        }
        if (user.isVerified() && user.getId() != null) {
            notificationSubscriptionRepository.deactivateByUser(user);
        }
        if (userInfo.lastVerificationDate() != null
                && (user.getLastVerifiedAt() == null || userInfo.lastVerificationDate().isAfter(user.getLastVerifiedAt()))) {
            user.setLastVerifiedAt(userInfo.lastVerificationDate());
        }
    }

    @Transactional
    public User disable(UUID bookingUserId) {
        User bookingUser = bookingUserRepository.findWithBezirkById(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        bookingUser.setActive(false);
        return bookingUser;
    }

    @Transactional
    public User enable(UUID bookingUserId) {
        User bookingUser = bookingUserRepository.findWithBezirkById(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        bookingUser.setActive(true);
        return bookingUser;
    }

    private String normalizeFoodsharingId(String foodsharingId) {
        String normalized = foodsharingId == null ? "" : foodsharingId.trim();
        if (!normalized.matches("\\d+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private User createUserWithIdentityLock(String normalizedFoodsharingId) {
        bezirkRepository.findFirstByOrderBySortOrderAscIdAsc()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND));
        return bookingUserRepository.findByFoodsharingIdIgnoreCaseForUpdate(normalizedFoodsharingId)
                .orElseGet(() -> {
                    User bookingUser = new User();
                    bookingUser.setFoodsharingId(normalizedFoodsharingId);
                    bookingUser.setName(normalizedFoodsharingId);
                    bookingUser.setActive(true);
                    return bookingUserRepository.saveAndFlush(bookingUser);
                });
    }
}
