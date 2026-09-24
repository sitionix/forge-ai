package com.sitionix.forgeagent.application;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.model.RemoteAccessPair;
import com.sitionix.forgeagent.domain.model.RemoteAccessRole;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteAccessPairDomainTest {
    @Test void aSingleActiveDirectionIsNotAConnectedBridge() {
        var pair=RemoteAccessPair.connector(UUID.randomUUID(),UUID.randomUUID(),Instant.now());
        var forward=UUID.randomUUID();var reverse=UUID.randomUUID();
        pair=pair.withForwardSession(forward);
        assertThat(pair.connected(RemoteAccessSessionStatus.ACTIVE,null)).isFalse();
        pair=pair.withReverseSession(reverse);
        assertThat(pair.connected(RemoteAccessSessionStatus.ACTIVE,RemoteAccessSessionStatus.PROVISIONING)).isFalse();
        assertThat(pair.connected(RemoteAccessSessionStatus.ACTIVE,RemoteAccessSessionStatus.ACTIVE)).isTrue();
    }

    @Test void aPairCannotBeReboundToAnUnrelatedSession() {
        var pair=RemoteAccessPair.connector(UUID.randomUUID(),UUID.randomUUID(),Instant.now());
        pair=pair.withForwardSession(UUID.randomUUID());
        var original=pair;
        assertThatThrownBy(() -> original.withForwardSession(UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> original.withReverseSession(original.forwardSessionId()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(pair.localForwardRole()).isEqualTo(RemoteAccessRole.ACCESSOR);
    }
}
