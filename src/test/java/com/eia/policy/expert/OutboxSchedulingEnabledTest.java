package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxDispatchService;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "eia.outbox.enabled=true",
        "eia.outbox.endpoint=http://127.0.0.1:65535/v1/events",
        "eia.outbox.interval=1h"
})
@ActiveProfiles("test")
class OutboxSchedulingEnabledTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private OutboxDispatchService dispatchService;

    @Test
    void enabledOutboxRegistersTheQuartzDispatchJob() throws Exception {
        assertThat(dispatchService).isNotNull();
        assertThat(context.getBeanNamesForType(OutboxDispatchService.class)).hasSize(1);

        assertThat(scheduler.getJobDetail(new JobKey("tx-outbox-dispatch", "eia-outbox")))
                .isNotNull();
        assertThat(scheduler.getTrigger(new TriggerKey("tx-outbox-dispatch-trigger", "eia-outbox")))
                .isNotNull();
    }
}
