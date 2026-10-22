package com.eia.policy.expert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds referral history once at startup for submissions that predate the
 * history table. A submission whose current {@code referral_*} columns carry a
 * referral but that has no history row gets exactly one seeded round, so
 * deployments can be upgraded without losing the visible referral. The
 * backfill is a no-op on every later start because submissions with history are
 * skipped.
 */
@Component
public class SubmissionReferralBackfillRunner implements ApplicationRunner {
    private static final Logger LOG =
            LoggerFactory.getLogger(SubmissionReferralBackfillRunner.class);

    private final SubmissionReferralRepository referralRepository;

    public SubmissionReferralBackfillRunner(SubmissionReferralRepository referralRepository) {
        this.referralRepository = referralRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /**
     * Runs the idempotent backfill. Exposed for tests so the startup path can
     * be exercised after history rows are removed.
     */
    public int backfill() {
        int inserted = referralRepository.backfillCurrentReferrals();
        if (inserted > 0) {
            LOG.info("Backfilled {} underwriting submission referral(s) into history",
                    inserted);
        }
        return inserted;
    }
}
