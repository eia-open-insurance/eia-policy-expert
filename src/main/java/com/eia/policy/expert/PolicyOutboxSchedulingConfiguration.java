package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxDispatchJob;
import com.eia.platform.libs.outbox.OutboxDispatchService;
import com.eia.platform.libs.outbox.OutboxProperties;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Schedules the outbox dispatcher only when {@code eia.outbox.enabled=true}.
 * Quartz is the only scheduler (SPEC 6.10, C-04).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "eia.outbox", name = "enabled", havingValue = "true")
public class PolicyOutboxSchedulingConfiguration {

    @Bean
    public ScheduledOutboxJob scheduledOutboxJob(
            Scheduler scheduler,
            OutboxDispatchService dispatchService,
            OutboxProperties properties) throws SchedulerException {
        scheduler.getContext().put(OutboxDispatchJob.SERVICE_CONTEXT_KEY, dispatchService);

        JobDetail job = JobBuilder.newJob(OutboxDispatchJob.class)
                .withIdentity("tx-outbox-dispatch", "eia-outbox")
                .withDescription("Dispatch pending transactional outbox events over HTTP")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("tx-outbox-dispatch-trigger", "eia-outbox")
                .forJob(job)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(
                                Math.max(100L, properties.effectiveInterval().toMillis()))
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();

        if (scheduler.checkExists(job.getKey())) {
            scheduler.deleteJob(job.getKey());
        }
        scheduler.scheduleJob(job, trigger);

        return new ScheduledOutboxJob(job.getKey(), trigger.getKey());
    }

    public record ScheduledOutboxJob(JobKey jobKey, TriggerKey triggerKey) {
    }
}
