package com.mapmyroad.backend.api.web;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App version from {@code X-App-Version}, compared numerically by major, minor and patch (API_SPEC.md section 7).
 * A pre-release or build suffix ({@code 1.0.3-debug}, {@code 1.2.0+42}) is accepted and ignored, so debug builds of
 * a release compare equal to that release.
 */
public record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {

    /** Each part is at most 6 digits so parsing can never overflow an int. */
    private static final Pattern FORMAT =
            Pattern.compile("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:[-+][0-9A-Za-z.+-]{1,40})?$");

    private static final Comparator<AppVersion> ORDER = Comparator.comparingInt(AppVersion::major)
            .thenComparingInt(AppVersion::minor)
            .thenComparingInt(AppVersion::patch);

    public AppVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version parts must not be negative");
        }
    }

    /** Parses {@code major.minor.patch[-suffix]}; empty when the value is missing or malformed. */
    public static Optional<AppVersion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(value.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AppVersion(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    /** Like {@link #parse} but for trusted configuration values, failing fast when malformed. */
    public static AppVersion parseRequired(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("Invalid version: " + value));
    }

    @Override
    public int compareTo(AppVersion other) {
        return ORDER.compare(this, other);
    }

    public boolean isOlderThan(AppVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
