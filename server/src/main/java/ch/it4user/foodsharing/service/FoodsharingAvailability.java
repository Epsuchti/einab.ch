package ch.it4user.foodsharing.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Circuit breaker for Foodsharing outages (maintenance page, 502/503/504, connection failures).
 * While the breaker is open, scheduled automation runs are skipped and only a cheap probe request is sent
 * every {@code outage-probe-interval}. Finished outages are remembered so that time-based rules
 * (linked-store booking window) do not count the time during which users could not book.
 * The state is kept in memory: after an application restart a running outage is detected again by the next failing call.
 */
@Component
public class FoodsharingAvailability {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingAvailability.class);
    private static final Duration OUTAGE_HISTORY = Duration.ofDays(3);

    private final RestClient probeClient;
    private final Duration probeInterval;
    private Instant lastOkAt = Instant.now();
    private Instant downSince;
    private Instant nextProbeAt;
    private final List<Outage> finishedOutages = new ArrayList<>();

    public FoodsharingAvailability(AppProperties appProperties,
                                   @Value("${app.foodsharing.automation.outage-probe-interval:PT10M}") Duration probeInterval) {
        this.probeClient = RestClient.builder().baseUrl(appProperties.getFoodsharing().getBaseUrl()).build();
        this.probeInterval = probeInterval;
    }

    private record Outage(Instant from, Instant to) {}

    /** True if the exception (or one of its causes) looks like Foodsharing being offline. */
    public static boolean looksLikeOutage(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ResourceAccessException) {
                return true;
            }
            if (t instanceof org.springframework.web.client.HttpServerErrorException server) {
                int status = server.getStatusCode().value();
                if (status == 502 || status == 503 || status == 504) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Time since which Foodsharing is considered offline, or null while it is online. */
    public synchronized Instant offlineSince() {
        return downSince;
    }

    public synchronized boolean isDown() {
        return downSince != null;
    }

    /**
     * Called for a failed Foodsharing call. Confirms with a probe that Foodsharing is really unreachable
     * (so e.g. a Telegram timeout does not open the breaker) and opens the breaker.
     *
     * @return true if Foodsharing is offline and the run should stop
     */
    public synchronized boolean reportFailure(String where, Throwable failure) {
        if (downSince != null) {
            return true;
        }
        if (!looksLikeOutage(failure) || probe()) {
            return false;
        }
        Instant now = Instant.now();
        // The site may have gone down any time since the last successful contact.
        downSince = lastOkAt.isBefore(now) ? lastOkAt : now;
        nextProbeAt = now.plus(probeInterval);
        log.warn("Foodsharing is offline ({}: {}); skipping all automations, next check in {} min.",
                where, summarize(failure), probeInterval.toMinutes());
        return true;
    }

    /**
     * Called at the start of every scheduled run.
     *
     * @return true if the run has to be skipped because Foodsharing is still offline
     */
    public synchronized boolean shouldSkipRun() {
        Instant now = Instant.now();
        if (downSince == null) {
            return false;
        }
        if (now.isBefore(nextProbeAt)) {
            log.debug("Foodsharing automation skipped: site offline since {}.", downSince);
            return true;
        }
        if (probe()) {
            markUp(now);
            return false;
        }
        nextProbeAt = now.plus(probeInterval);
        log.warn("Foodsharing is still offline since {}; next check in {} min.", downSince, probeInterval.toMinutes());
        return true;
    }

    /** Called after a run finished without an outage; it narrows down when the site was last known to be up. */
    public synchronized void markRunSucceeded() {
        if (downSince == null) {
            lastOkAt = Instant.now();
        }
    }

    /**
     * Time within {@code [from, to]} during which Foodsharing was offline, including a running outage.
     */
    public synchronized Duration outageOverlap(Instant from, Instant to) {
        Duration total = Duration.ZERO;
        List<Outage> all = new ArrayList<>(finishedOutages);
        if (downSince != null) {
            all.add(new Outage(downSince, Instant.now()));
        }
        for (Outage outage : all) {
            Instant start = outage.from().isAfter(from) ? outage.from() : from;
            Instant end = outage.to().isBefore(to) ? outage.to() : to;
            if (end.isAfter(start)) {
                total = total.plus(Duration.between(start, end));
            }
        }
        return total;
    }

    /** Moves a deadline back by the time Foodsharing was offline between {@code start} and the (moving) deadline. */
    public Instant extendDeadline(Instant start, Instant deadline) {
        Instant extended = deadline;
        for (int i = 0; i < 5; i++) {
            Instant next = deadline.plus(outageOverlap(start, extended));
            if (next.equals(extended)) {
                break;
            }
            extended = next;
        }
        return extended;
    }

    private void markUp(Instant now) {
        log.info("Foodsharing is back online after an outage since {}; automations resume.", downSince);
        finishedOutages.add(new Outage(downSince, now));
        finishedOutages.removeIf(outage -> outage.to().isBefore(now.minus(OUTAGE_HISTORY)));
        downSince = null;
        nextProbeAt = null;
        lastOkAt = now;
    }

    /** Cheap, unauthenticated request: reachable unless the site answers 5xx or cannot be contacted. */
    private boolean probe() {
        try {
            probeClient.get().uri("/").retrieve().onStatus(HttpStatusCode::is4xxClientError, (request, response) -> { }).toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            return !ex.getStatusCode().is5xxServerError();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String summarize(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException response) {
                return "HTTP " + response.getStatusCode().value();
            }
        }
        return failure.getClass().getSimpleName();
    }
}
