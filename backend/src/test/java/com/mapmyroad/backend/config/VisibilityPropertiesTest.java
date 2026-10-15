package com.mapmyroad.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class VisibilityPropertiesTest {

    @Test
    void defaultsMatchSecurityT2AndApiSpec() {
        VisibilityProperties p = VisibilityProperties.defaults();
        assertThat(p.minSamples()).isEqualTo(3);
        assertThat(p.minDevices()).isEqualTo(2);
        assertThat(p.minDevicesLowTraffic()).isEqualTo(3);
        assertThat(p.lowTrafficHighways()).containsExactly("residential", "service", "living_street", "unclassified");
        assertThat(p.excludedAccessValues()).containsExactly("private", "no");
        assertThat(p.hazardMinConfidence()).isEqualTo(0.3);
    }

    @Test
    void rejectsInconsistentValues() {
        assertThatThrownBy(() -> new VisibilityProperties(0, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // The low-traffic threshold may never be weaker than the ordinary one.
        assertThatThrownBy(() -> new VisibilityProperties(null, 3, 2, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisibilityProperties(null, null, null, null, null, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisibilityProperties(null, null, null, List.of("a,b"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisibilityProperties(null, null, null, null, List.of(" "), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
