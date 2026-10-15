package com.mapmyroad.backend.service.geocode;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.GeocodeProperties;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import com.mapmyroad.backend.integration.photon.PhotonClient;
import com.mapmyroad.backend.integration.photon.PhotonPlace;
import com.mapmyroad.backend.integration.photon.PhotonReverseRequest;
import com.mapmyroad.backend.integration.photon.PhotonSearchRequest;
import com.mapmyroad.backend.integration.photon.PhotonUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Place search and reverse geocoding through Photon with a two-level cache (M4-13, API_SPEC.md section 5.7,
 * ARCHITECTURE.md section 9.3).
 *
 * <ul>
 *   <li>Photon is asked to restrict results to the supported region ({@code bbox}), and anything it still returns
 *       outside the region is dropped before caching.</li>
 *   <li>A search bias outside the supported region is ignored rather than rejected (the user may be travelling);
 *       inside, it is rounded to about 1 km before it reaches the key or Photon (see {@link GeocodeCacheKeys}), so
 *       the exact user location never leaves the server.</li>
 *   <li>Reverse lookups outside the supported region are rejected with 422 UNSUPPORTED_REGION.</li>
 *   <li>A requested language Photon does not support is omitted upstream (Photon then uses local names).</li>
 * </ul>
 *
 * <p>Metrics: {@code mmr.geocode.requests{kind, source=memory|db|upstream}} and
 * {@code mmr.geocode.upstream.errors{kind, reason}}. Search text and coordinates are never logged.
 */
@Service
public class GeocodeService {

    static final String REQUESTS_METRIC = "mmr.geocode.requests";
    static final String UPSTREAM_ERRORS_METRIC = "mmr.geocode.upstream.errors";
    static final String UNAVAILABLE_DETAIL = "Place search is temporarily unavailable. Retry later.";

    private enum Kind {
        SEARCH,
        REVERSE,
    }

    private final PhotonClient photon;
    private final GeocodeCache cache;
    private final GeocodeProperties properties;
    private final SupportedRegion region;
    private final MeterRegistry meters;

    public GeocodeService(
            PhotonClient photon,
            GeocodeCache cache,
            GeocodeProperties properties,
            MmrProperties mmrProperties,
            MeterRegistry meters) {
        this.photon = photon;
        this.cache = cache;
        this.properties = properties;
        this.region = mmrProperties.supportedRegion();
        this.meters = meters;
    }

    public GeocodeLookup search(SearchQuery query) {
        String text = GeocodeCacheKeys.normalizeQuery(query.text());
        String lang = upstreamLanguage(query.lang());
        GeoPoint bias = roundedBias(query.bias());
        String key = GeocodeCacheKeys.searchKey(text, lang, query.limit(), bias);
        PhotonSearchRequest request = new PhotonSearchRequest(
                text,
                query.limit(),
                lang,
                bias == null ? null : bias.lat(),
                bias == null ? null : bias.lon(),
                region);
        return lookup(Kind.SEARCH, key, query.limit(), () -> photon.search(request));
    }

    public GeocodeLookup reverse(ReverseQuery query) {
        if (!region.contains(query.point().lat(), query.point().lon())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_REGION, "The point is outside the supported region.");
        }
        String lang = upstreamLanguage(query.lang());
        GeoPoint rounded = new GeoPoint(
                GeocodeCacheKeys.round(query.point().lat(), GeocodeCacheKeys.REVERSE_DECIMALS),
                GeocodeCacheKeys.round(query.point().lon(), GeocodeCacheKeys.REVERSE_DECIMALS));
        String key = GeocodeCacheKeys.reverseKey(rounded, lang);
        PhotonReverseRequest request = new PhotonReverseRequest(rounded.lat(), rounded.lon(), lang);
        return lookup(Kind.REVERSE, key, 1, () -> photon.reverse(request));
    }

    private GeocodeLookup lookup(Kind kind, String key, int limit, Supplier<List<PhotonPlace>> upstream) {
        Optional<GeocodeCache.Hit> hit = cache.get(key);
        if (hit.isPresent()) {
            count(kind, hit.get().source().name());
            return new GeocodeLookup(hit.get().results(), true);
        }
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, UNAVAILABLE_DETAIL);
        }
        List<PhotonPlace> places;
        try {
            places = upstream.get();
        } catch (PhotonUnavailableException e) {
            Counter.builder(UPSTREAM_ERRORS_METRIC)
                    .tag("kind", tag(kind.name()))
                    .tag("reason", tag(e.reason().name()))
                    .register(meters)
                    .increment();
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, UNAVAILABLE_DETAIL);
        }
        List<GeocodeResult> results = inRegion(places, limit);
        cache.put(key, results);
        count(kind, "UPSTREAM");
        return new GeocodeLookup(results, false);
    }

    /** Drops places outside the supported region and caps the count. */
    List<GeocodeResult> inRegion(List<PhotonPlace> places, int limit) {
        return places.stream()
                .filter(place -> region.contains(place.lat(), place.lon()))
                .limit(limit)
                .map(GeocodeService::toResult)
                .toList();
    }

    private static GeocodeResult toResult(PhotonPlace place) {
        return new GeocodeResult(place.name(), place.displayName(), place.lat(), place.lon(), place.type());
    }

    private String upstreamLanguage(String requested) {
        return properties.supportsLanguage(requested) ? requested : null;
    }

    private GeoPoint roundedBias(GeoPoint bias) {
        if (bias == null || !region.contains(bias.lat(), bias.lon())) {
            return null;
        }
        return new GeoPoint(
                GeocodeCacheKeys.round(bias.lat(), GeocodeCacheKeys.BIAS_DECIMALS),
                GeocodeCacheKeys.round(bias.lon(), GeocodeCacheKeys.BIAS_DECIMALS));
    }

    private void count(Kind kind, String source) {
        Counter.builder(REQUESTS_METRIC)
                .tag("kind", tag(kind.name()))
                .tag("source", tag(source))
                .register(meters)
                .increment();
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
