package com.mapmyroad.backend.api.geocode;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.FieldProblem;
import com.mapmyroad.backend.service.geocode.GeoPoint;
import com.mapmyroad.backend.service.geocode.GeocodeCacheKeys;
import com.mapmyroad.backend.service.geocode.ReverseQuery;
import com.mapmyroad.backend.service.geocode.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns raw query parameters of {@code GET /v1/geocode} and {@code GET /v1/geocode/reverse} into validated
 * queries, collecting every problem into one 400 INVALID_REQUEST (API_SPEC.md section 3). Messages never echo the
 * submitted value.
 *
 * <ul>
 *   <li>{@code q}: required, 3 to 200 characters after trimming and collapsing whitespace.</li>
 *   <li>{@code limit}: 1 to 10, default 5.</li>
 *   <li>{@code lat}/{@code lon}: both or neither for search, both for reverse, finite and within WGS84 range.</li>
 *   <li>{@code lang}: optional BCP 47-like tag ({@code en}, {@code mr}, {@code en-IN}); only the lowercased
 *       primary subtag is kept.</li>
 * </ul>
 */
public final class GeocodeRequestParser {

    static final int MIN_QUERY_LENGTH = 3;
    static final int MAX_QUERY_LENGTH = 200;
    /** Raw length guard so huge inputs are rejected before normalization. */
    static final int MAX_RAW_QUERY_LENGTH = 1000;
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 10;
    static final String INVALID_DETAIL = "One or more fields are invalid.";

    private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?");

    private GeocodeRequestParser() {}

    public static SearchQuery search(String q, Double lat, Double lon, Integer limit, String lang) {
        List<FieldProblem> problems = new ArrayList<>();
        String text = query(q, problems);
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            problems.add(new FieldProblem("limit", "must be between 1 and " + MAX_LIMIT));
        }
        GeoPoint bias = null;
        if (lat == null && lon != null) {
            problems.add(new FieldProblem("lat", "is required when lon is given"));
        } else if (lat != null && lon == null) {
            problems.add(new FieldProblem("lon", "is required when lat is given"));
        } else if (lat != null) {
            bias = point(lat, lon, problems);
        }
        String language = language(lang, problems);
        throwIfAny(problems);
        return new SearchQuery(text, effectiveLimit, language, bias);
    }

    public static ReverseQuery reverse(Double lat, Double lon, String lang) {
        List<FieldProblem> problems = new ArrayList<>();
        if (lat == null) {
            problems.add(new FieldProblem("lat", "is required"));
        }
        if (lon == null) {
            problems.add(new FieldProblem("lon", "is required"));
        }
        GeoPoint point = lat != null && lon != null ? point(lat, lon, problems) : null;
        String language = language(lang, problems);
        throwIfAny(problems);
        return new ReverseQuery(point, language);
    }

    private static String query(String q, List<FieldProblem> problems) {
        if (q == null || q.isBlank()) {
            problems.add(new FieldProblem("q", "is required"));
            return null;
        }
        String text = q.length() > MAX_RAW_QUERY_LENGTH ? null : GeocodeCacheKeys.collapse(q);
        if (text == null || text.length() < MIN_QUERY_LENGTH || text.length() > MAX_QUERY_LENGTH) {
            problems.add(new FieldProblem(
                    "q", "must be " + MIN_QUERY_LENGTH + " to " + MAX_QUERY_LENGTH + " characters"));
            return null;
        }
        return text;
    }

    private static GeoPoint point(double lat, double lon, List<FieldProblem> problems) {
        boolean valid = true;
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            problems.add(new FieldProblem("lat", "must be between -90 and 90"));
            valid = false;
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            problems.add(new FieldProblem("lon", "must be between -180 and 180"));
            valid = false;
        }
        return valid ? new GeoPoint(lat, lon) : null;
    }

    private static String language(String lang, List<FieldProblem> problems) {
        if (lang == null || lang.isBlank()) {
            return null;
        }
        String tag = lang.trim();
        if (!LANGUAGE_TAG.matcher(tag).matches()) {
            problems.add(new FieldProblem("lang", "must be a language code such as en"));
            return null;
        }
        return tag.split("[-_]", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static void throwIfAny(List<FieldProblem> problems) {
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, INVALID_DETAIL, Map.of("errors", List.copyOf(problems)));
        }
    }
}
