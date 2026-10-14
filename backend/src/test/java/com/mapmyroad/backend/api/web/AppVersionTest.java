package com.mapmyroad.backend.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AppVersionTest {

    @ParameterizedTest
    @CsvSource({
        "1.0.3, 1, 0, 3",
        "10.20.30, 10, 20, 30",
        "1.0.3-debug, 1, 0, 3",
        "2.1.0+42, 2, 1, 0",
        "' 1.2.3 ', 1, 2, 3"
    })
    void parsesSemanticVersions(String value, int major, int minor, int patch) {
        assertThat(AppVersion.parse(value)).contains(new AppVersion(major, minor, patch));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.0", "1.0.0.0", "v1.0.0", "1.a.0", "-1.0.0", "1.0.0-", "1234567.0.0", "1..0"})
    void rejectsMalformedVersions(String value) {
        assertThat(AppVersion.parse(value)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(AppVersion.parse(null)).isEmpty();
    }

    @Test
    void comparesNumericallyNotLexically() {
        assertThat(v("1.10.0").compareTo(v("1.9.9"))).isGreaterThan(0);
        assertThat(v("2.0.0").isOlderThan(v("10.0.0"))).isTrue();
        assertThat(v("1.0.2").isOlderThan(v("1.0.3"))).isTrue();
        assertThat(v("1.0.3").isOlderThan(v("1.0.3"))).isFalse();
        assertThat(v("1.1.0").isOlderThan(v("1.0.9"))).isFalse();
    }

    @Test
    void suffixIsIgnoredInComparison() {
        assertThat(v("1.0.3-debug").compareTo(v("1.0.3"))).isEqualTo(0);
        assertThat(v("1.0.3-debug").toString()).isEqualTo("1.0.3");
    }

    @Test
    void parseRequiredFailsFastOnBadConfiguration() {
        assertThatThrownBy(() -> AppVersion.parseRequired("latest")).isInstanceOf(IllegalArgumentException.class);
    }

    private static AppVersion v(String value) {
        return AppVersion.parseRequired(value);
    }
}
