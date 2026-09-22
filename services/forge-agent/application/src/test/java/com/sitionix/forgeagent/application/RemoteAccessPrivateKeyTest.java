package com.sitionix.forgeagent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.RemoteAccessPrivateKey;
import java.beans.Introspector;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RemoteAccessPrivateKeyTest {

    @Test
    void copiesInputAndOutputInsteadOfExposingSecretStorage() {
        final byte[] supplied = "private-material".getBytes();
        final var key = new RemoteAccessPrivateKey(supplied);
        supplied[0] = 'X';

        final byte[] copied = key.copyBytes();
        copied[1] = 'X';

        assertThat(key.copyBytes()).isEqualTo("private-material".getBytes());
        key.close();
    }

    @Test
    void closeZeroizesOwnedBytesAndPreventsFurtherCopies() throws Exception {
        final var key = new RemoteAccessPrivateKey("private-material".getBytes());
        final var bytesField = RemoteAccessPrivateKey.class.getDeclaredField("bytes");
        bytesField.setAccessible(true);

        key.close();

        assertThat((byte[]) bytesField.get(key)).containsOnly((byte) 0);
        assertThatThrownBy(key::copyBytes).isInstanceOf(IllegalStateException.class);
        key.close();
    }

    @Test
    void stringBeanIntrospectionAndJacksonDoNotExposePrivateMaterial() throws Exception {
        final var key = new RemoteAccessPrivateKey("private-material".getBytes());

        assertThat(key.toString()).doesNotContain("private-material").contains("REDACTED");
        assertThat(Arrays.stream(Introspector.getBeanInfo(RemoteAccessPrivateKey.class).getPropertyDescriptors())
                .map(descriptor -> descriptor.getName()))
                .containsExactly("class");
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(key))
                .hasMessageNotContaining("private-material");
        key.close();
    }
}
