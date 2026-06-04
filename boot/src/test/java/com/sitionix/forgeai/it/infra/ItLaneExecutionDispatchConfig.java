package com.sitionix.forgeai.it.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
@Profile("it")
public class ItLaneExecutionDispatchConfig {

    @Bean
    @Primary
    public TaskExecutor itLaneExecutionTaskExecutor() {
        return new SyncTaskExecutor();
    }
}
