package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.entity.UserRefreshJob;
import ch.it4user.foodsharing.openapi.model.UserRefreshProgress;
import ch.it4user.foodsharing.repository.UserRefreshJobRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Refreshes user data from Foodsharing in the background, one user per interval, because Foodsharing blocks
 * clients that send too many requests. One refresh itself needs a few Foodsharing requests, so the interval is the
 * pause between two users, not between two HTTP requests.
 */
@Service
public class UserRefreshQueueService {

    private static final Logger log = LoggerFactory.getLogger(UserRefreshQueueService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RATE_LIMIT_PAUSE = Duration.ofMinutes(2);

    private final UserRefreshJobRepository jobRepository;
    private final BookingUserService bookingUserService;
    private final FoodsharingAvailability foodsharingAvailability;
    private final long intervalMillis;
    private volatile Instant pausedUntil = Instant.EPOCH;

    public UserRefreshQueueService(UserRefreshJobRepository jobRepository,
                                   BookingUserService bookingUserService,
                                   FoodsharingAvailability foodsharingAvailability,
                                   @Value("${app.user-refresh.interval-ms:2000}") long intervalMillis) {
        this.jobRepository = jobRepository;
        this.bookingUserService = bookingUserService;
        this.foodsharingAvailability = foodsharingAvailability;
        this.intervalMillis = Math.max(intervalMillis, 500);
    }

    /** Queues a refresh for every user; a user that is already waiting is moved into the new batch instead. */
    @Transactional
    public int enqueue(Bezirk bezirk, Collection<User> users) {
        if (users.isEmpty()) {
            return 0;
        }
        UUID batchId = UUID.randomUUID();
        Map<UUID, UserRefreshJob> waiting = new HashMap<>();
        for (UserRefreshJob job : jobRepository.findAllByUserInAndStatus(users, UserRefreshJob.PENDING)) {
            waiting.put(job.getUser().getId(), job);
        }
        for (User user : users) {
            UserRefreshJob job = waiting.get(user.getId());
            if (job == null) {
                job = new UserRefreshJob();
                job.setUser(user);
                job.setBezirk(bezirk);
            }
            job.setBatchId(batchId);
            jobRepository.save(job);
        }
        return users.size();
    }

    @Transactional(readOnly = true)
    public UserRefreshProgress progress(Bezirk bezirk) {
        UserRefreshProgress progress = new UserRefreshProgress();
        progress.setIntervalSeconds(BigDecimal.valueOf(intervalMillis, 3));
        Optional<UserRefreshJob> latest = jobRepository.findFirstByBezirkOrderByCreatedAtDesc(bezirk);
        if (latest.isEmpty()) {
            progress.setTotal(0);
            progress.setDone(0);
            progress.setFailed(0);
            progress.setPending(0);
            progress.setActive(false);
            return progress;
        }
        UUID batchId = latest.get().getBatchId();
        int pending = (int) jobRepository.countByBatchIdAndStatus(batchId, UserRefreshJob.PENDING);
        progress.setTotal((int) jobRepository.countByBatchId(batchId));
        progress.setDone((int) jobRepository.countByBatchIdAndStatus(batchId, UserRefreshJob.DONE));
        progress.setFailed((int) jobRepository.countByBatchIdAndStatus(batchId, UserRefreshJob.FAILED));
        progress.setPending(pending);
        progress.setActive(pending > 0);
        return progress;
    }

    @Scheduled(initialDelay = 15_000, fixedDelayString = "${app.user-refresh.interval-ms:2000}")
    public void processNext() {
        if (Instant.now().isBefore(pausedUntil) || foodsharingAvailability.isDown()) {
            return;
        }
        Optional<UserRefreshJob> next = jobRepository.findFirstByStatusOrderByCreatedAtAsc(UserRefreshJob.PENDING);
        if (next.isEmpty()) {
            return;
        }
        UserRefreshJob job = next.get();
        try {
            bookingUserService.refreshUserData(job.getUser().getId());
            finish(job, UserRefreshJob.DONE, null);
        } catch (HttpClientErrorException.TooManyRequests ex) {
            pausedUntil = Instant.now().plus(RATE_LIMIT_PAUSE);
            log.warn("Foodsharing rate limit hit, pausing the user refresh queue for {}", RATE_LIMIT_PAUSE);
        } catch (HttpClientErrorException ex) {
            // The user cannot be read (e.g. 404): retrying does not help.
            finish(job, UserRefreshJob.FAILED, "HTTP_" + ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            if (FoodsharingAvailability.looksLikeOutage(ex) || job.getAttempts() + 1 < MAX_ATTEMPTS) {
                jobRepository.countAttempt(job.getId(), Instant.now());
            } else {
                finish(job, UserRefreshJob.FAILED, ex.getClass().getSimpleName());
            }
        }
    }

    private void finish(UserRefreshJob job, String status, String errorCode) {
        jobRepository.finish(job.getId(), status, errorCode, Instant.now());
    }
}
