package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ForgeAiApplicationIT {

    @Autowired
    private TestManager testManager;

    @Test
    void givenApplicationContext_whenInjected_thenContextLoads() {
        //given when

        //then
        assertThat(this.testManager).isNotNull();
    }
}
