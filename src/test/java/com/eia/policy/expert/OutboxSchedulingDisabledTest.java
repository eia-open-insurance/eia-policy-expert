package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxDispatchService;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OutboxSchedulingDisabledTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private Scheduler scheduler;

    @Test
    void disabledOutboxCreatesNoDispatcherBeansOrJob() throws Exception {
        assertThat(context.getBeanNamesForType(OutboxDispatchService.class)).isEmpty();
        assertThat(scheduler.getJobKeys(GroupMatcher.jobGroupEquals("eia-outbox"))).isEmpty();
    }
}
