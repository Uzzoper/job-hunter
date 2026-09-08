package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Shared owner-email self-match guard. It is applied by every ingestion stage that can
 * attach a contact email to a {@link com.juanperuzzo.job_hunter.domain.model.Job}:
 * description extraction in {@link JobNormalizer} and company-site enrichment in the
 * company-site enricher.
 * <p>
 * <strong>Global by design:</strong> the {@code jobs} table is a global table and this
 * guard runs regardless of the requesting user. The current deployment is single-owner,
 * so the guard and the V5 data fix are intentionally global; the code is generic, with
 * no hardcoded e-mail. A future multi-user deployment would need to scope this guard per
 * user, which is explicitly out of scope today.
 * <p>
 * Owner e-mails are loaded lazily once and cached (normalizers and the enricher are
 * singletons). If the owner set cannot be loaded (e.g. DB unreadable) the guard is
 * <strong>fail-closed</strong>: candidate contact e-mails are discarded instead of being
 * accepted unverified, so an owner address can never slip through, and the load is
 * retried on the next call. The failure warning is logged once to avoid per-job spam.
 */
public class OwnerEmailGuard {

    private static final Logger log = LoggerFactory.getLogger(OwnerEmailGuard.class);

    private final UserRepository userRepository;
    private volatile Set<String> ownerEmails;
    private final AtomicBoolean loadWarningLogged = new AtomicBoolean();

    public OwnerEmailGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Return the candidate contact email unchanged when it is safe to keep, or {@code null}
     * when it matches a registered user's email (self-match) — or when the owner email set
     * could not be loaded (fail-closed).
     *
     * @param context human/URL context used only in the WARN log line
     */
    public String discardIfOwnerEmail(String contactEmail, String context) {
        if (contactEmail == null || contactEmail.isBlank() || userRepository == null) {
            return contactEmail;
        }
        var owners = ownerEmails();
        if (owners == null) {
            // Fail-closed: without the owner set we cannot prove this address is not an
            // owner's, so it is discarded rather than persisted unverified.
            return null;
        }
        var normalized = contactEmail.trim().toLowerCase(Locale.ROOT);
        if (owners.contains(normalized)) {
            log.warn("Discarding contact email '{}' for {}: matches a registered user email",
                    contactEmail, context);
            return null;
        }
        return contactEmail;
    }

    private Set<String> ownerEmails() {
        var cached = ownerEmails;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ownerEmails == null) {
                try {
                    var emails = userRepository.findAllEmails();
                    // Nil-guard: an unexpected null list is treated as an empty set.
                    var emailList = emails != null ? emails : List.<String>of();
                    ownerEmails = emailList.stream()
                            .filter(email -> email != null && !email.isBlank())
                            .map(email -> email.trim().toLowerCase(Locale.ROOT))
                            .collect(Collectors.toCollection(HashSet::new));
                } catch (Exception e) {
                    // Fail-closed, retried: ownerEmails stays null so the next call retries
                    // the load (the DB may be back up); the warning is logged once only.
                    if (loadWarningLogged.compareAndSet(false, true)) {
                        log.warn("Could not load owner emails for the self-match guard; contact emails "
                                + "are discarded (fail-closed) until the owner set loads: {}", e.getMessage());
                    } else {
                        log.debug("Owner email load still failing (fail-closed): {}", e.getMessage());
                    }
                    return null;
                }
            }
            return ownerEmails;
        }
    }
}