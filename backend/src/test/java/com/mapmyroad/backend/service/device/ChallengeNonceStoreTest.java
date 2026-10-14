package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.DeviceProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChallengeNonceStoreTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T08:00:00Z"));
    private final ChallengeNonceStore store = new ChallengeNonceStore(
            new DeviceProperties(false, null, null, null, null, null, Duration.ofMinutes(5)), clock);

    @Test
    void nonceCanBeConsumedExactlyOnce() {
        String nonce = store.issue();

        assertThat(store.consume(nonce)).isTrue();
        assertThat(store.consume(nonce)).isFalse();
    }

    @Test
    void unknownOrMissingNonceIsRejected() {
        assertThat(store.consume("never-issued")).isFalse();
        assertThat(store.consume(null)).isFalse();
    }

    @Test
    void expiredNonceIsRejected() {
        String nonce = store.issue();

        clock.advance(Duration.ofMinutes(5));

        assertThat(store.consume(nonce)).isFalse();
    }

    @Test
    void nonceIsValidJustBeforeExpiry() {
        String nonce = store.issue();

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));

        assertThat(store.consume(nonce)).isTrue();
    }

    @Test
    void reportsConfiguredTtl() {
        assertThat(store.ttl()).isEqualTo(Duration.ofMinutes(5));
    }
}
