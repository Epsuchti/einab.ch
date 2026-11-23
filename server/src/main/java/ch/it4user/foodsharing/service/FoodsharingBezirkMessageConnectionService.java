package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingBezirkMessageConnection;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.repository.FoodsharingBezirkMessageConnectionRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoodsharingBezirkMessageConnectionService {

    private final FoodsharingBezirkMessageConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final FoodsharingClient foodsharingClient;
    private final FoodsharingMessageTelegramService telegramService;

    public FoodsharingBezirkMessageConnectionService(
            FoodsharingBezirkMessageConnectionRepository connectionRepository,
            UserRepository userRepository,
            FoodsharingClient foodsharingClient,
            FoodsharingMessageTelegramService telegramService) {
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.foodsharingClient = foodsharingClient;
        this.telegramService = telegramService;
    }

    @Transactional(readOnly = true)
    public Optional<FoodsharingBezirkMessageConnection> findForRecipient(String foodsharingId) {
        if (foodsharingId == null || foodsharingId.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByFoodsharingIdIgnoreCase(foodsharingId.trim())
                .map(User::getBezirk)
                .flatMap(connectionRepository::findByBezirk)
                .filter(this::isConfigured);
    }

    @Transactional(readOnly = true)
    public ConnectionStatus status(Bezirk bezirk) {
        return connectionRepository.findByBezirk(bezirk)
                .map(connection -> new ConnectionStatus(connection.getFoodsharingEmail(), isConfigured(connection)))
                .orElseGet(() -> new ConnectionStatus(null, false));
    }

    @Transactional
    public void update(Bezirk bezirk, String email, String password) {
        if (email == null && password == null) {
            return;
        }

        Optional<FoodsharingBezirkMessageConnection> existing = connectionRepository.findByBezirk(bezirk);
        String normalizedEmail = email == null
                ? existing.map(FoodsharingBezirkMessageConnection::getFoodsharingEmail).orElse(null)
                : normalize(email);
        String normalizedPassword = normalizePassword(password);

        if (normalizedEmail == null) {
            if (normalizedPassword != null) {
                throw validationError("Foodsharing messaging email is required when setting a password.");
            }
            existing.ifPresent(connection -> {
                telegramService.deleteForConnection(connection);
                connectionRepository.delete(connection);
            });
            return;
        }

        if (normalizedPassword == null) {
            if (existing.isEmpty()) {
                throw validationError("Foodsharing messaging password is required when configuring an email.");
            }
            if (!normalizedEmail.equalsIgnoreCase(existing.get().getFoodsharingEmail())) {
                throw validationError("Foodsharing messaging password is required when changing the email.");
            }
            return;
        }

        FoodsharingBezirkMessageConnection connection = existing.orElseGet(FoodsharingBezirkMessageConnection::new);
        connection.setBezirk(bezirk);
        foodsharingClient.configureBezirkMessageConnection(connection, normalizedEmail, normalizedPassword);
        connectionRepository.save(connection);
    }

    private boolean isConfigured(FoodsharingBezirkMessageConnection connection) {
        return connection.getFoodsharingEmail() != null && !connection.getFoodsharingEmail().isBlank()
                && connection.getFoodsharingPasswordCiphertext() != null
                && !connection.getFoodsharingPasswordCiphertext().isBlank();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizePassword(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private ApiException validationError(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(detail));
    }

    public record ConnectionStatus(String email, boolean configured) {
    }
}
