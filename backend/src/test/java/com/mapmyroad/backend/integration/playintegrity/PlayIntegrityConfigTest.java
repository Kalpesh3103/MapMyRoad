package com.mapmyroad.backend.integration.playintegrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlayIntegrityConfigTest {

    @Test
    void prodProfileRefusesToStartWithoutEnforcement() {
        assertThatThrownBy(() -> PlayIntegrityConfig.notEnforced(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void otherProfilesGetTheAcceptAllVerifier() {
        PlayIntegrityVerifier verifier = PlayIntegrityConfig.notEnforced(false);

        assertThat(verifier).isInstanceOf(NoopPlayIntegrityVerifier.class);
        assertThat(verifier.verify("anything", "nonce").accepted()).isTrue();
    }
}
