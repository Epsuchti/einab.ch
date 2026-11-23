package ch.it4user.foodsharing.service;

import java.time.Instant;

/**
 * @param verificationDate     first successful Foodsharing verification (used by the cleaning rule)
 * @param verified             current verification state, {@code null} when it could not be determined
 * @param lastVerificationDate most recent successful Foodsharing verification
 * @param verificationLookupFailed the verification request failed, so the verification dates are unknown (not absent)
 */
public record FoodsharingUserInfo(String foodsharingId, String name, String phoneNumber, boolean sleeping,
                                  Instant verificationDate, Boolean verified, Instant lastVerificationDate,
                                  boolean verificationLookupFailed) {

    public FoodsharingUserInfo(String foodsharingId, String name, String phoneNumber, boolean sleeping,
                               Instant verificationDate, Boolean verified, Instant lastVerificationDate) {
        this(foodsharingId, name, phoneNumber, sleeping, verificationDate, verified, lastVerificationDate, false);
    }

    public static FoodsharingUserInfo unknown(String foodsharingId) {
        return new FoodsharingUserInfo(foodsharingId, null, null, false, null, null, null);
    }

    public static FoodsharingUserInfo lookupFailed(String foodsharingId) {
        return new FoodsharingUserInfo(foodsharingId, null, null, false, null, null, null, true);
    }
}
