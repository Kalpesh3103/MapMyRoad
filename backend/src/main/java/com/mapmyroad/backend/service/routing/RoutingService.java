package com.mapmyroad.backend.service.routing;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.RoutingProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import com.mapmyroad.backend.integration.polyline.LatLon;
import com.mapmyroad.backend.integration.polyline.Polyline;
import com.mapmyroad.backend.integration.valhalla.RouteOption;
import com.mapmyroad.backend.integration.valhalla.ValhallaCosting;
import com.mapmyroad.backend.integration.valhalla.ValhallaRouter;
import com.mapmyroad.backend.integration.valhalla.ValhallaUnavailableException;
import com.mapmyroad.backend.repo.routing.HazardNearRoute;
import com.mapmyroad.backend.repo.routing.RouteDataRepository;
import com.mapmyroad.backend.repo.routing.RouteWkt;
import com.mapmyroad.backend.repo.routing.SegmentNearRoute;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Route candidates with comfort scores and coverage (M4-14, ARCHITECTURE.md 9.1, ADR-0004): ask Valhalla for the
 * main route and alternates, drop near-duplicates, score each against hazards and segment roughness, label them and
 * pick the recommended one for the requested preference.
 *
 * <p>Privacy (API_SPEC.md 5.8): origin and destination are never stored, logged or put into metrics or exception
 * messages. Only the candidate geometries are kept, in memory, for about 15 minutes ({@link RouteCandidateStore}).
 */
@Service
public class RoutingService {

    static final String METRIC_REQUESTS = "mmr.routes.requests";
    static final String METRIC_DURATION = "mmr.routes.duration";

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final ValhallaRouter router;
    private final RouteDataRepository repository;
    private final RouteCandidateStore store;
    private final RoutingProperties properties;
    private final SupportedRegion region;
    private final ComfortScorer scorer;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public RoutingService(
            ValhallaRouter router,
            RouteDataRepository repository,
            RouteCandidateStore store,
            RoutingProperties properties,
            MmrProperties mmrProperties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.router = router;
        this.repository = repository;
        this.store = store;
        this.properties = properties;
        this.region = mmrProperties.supportedRegion();
        this.scorer = new ComfortScorer(properties);
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * @throws ApiException 400 INVALID_REQUEST when the points are too close, 422 UNSUPPORTED_REGION, 422 NO_ROUTE,
     *     503 UPSTREAM_UNAVAILABLE when Valhalla cannot be reached
     */
    public RouteCandidates candidates(RouteQuery query) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            RouteCandidates result = compute(query);
            outcome = "ok";
            return result;
        } catch (ApiException e) {
            outcome = e.code().name().toLowerCase(Locale.ROOT);
            throw e;
        } finally {
            sample.stop(meterRegistry.timer(METRIC_DURATION));
            meterRegistry.counter(METRIC_REQUESTS, "outcome", outcome).increment();
        }
    }

    private RouteCandidates compute(RouteQuery query) {
        validate(query);
        List<RouteOption> options = requestOptions(query);
        List<RouteOption> distinct = CandidateDeduplicator.distinct(options, properties.maxCandidates());

        Instant now = clock.instant();
        List<ScoredRoute> scored = new ArrayList<>(distinct.size());
        for (RouteOption option : distinct) {
            List<LatLon> points = decode(option);
            if (points.size() < 2) {
                continue;
            }
            CandidateScore score = score(option, points, now);
            scored.add(new ScoredRoute(
                    "c" + (scored.size() + 1), option.encodedPolyline6(), option.distanceM(), option.durationSec(),
                    score));
        }
        if (scored.isEmpty()) {
            throw new ApiException(ErrorCode.NO_ROUTE, "No route found between the points.");
        }

        List<RankedCandidate> ranked = CandidateRanker.rank(scored, query.preference());
        RankedCandidate recommended =
                ranked.stream().filter(RankedCandidate::recommended).findFirst().orElse(ranked.get(0));
        RouteCandidates result = new RouteCandidates(UUID.randomUUID(), ranked, recommended.score().coverage());
        store.put(result, query.vehicleType());
        log.info(
                "Route candidates: valhalla={}, distinct={}, recommended={}, coverage={}",
                options.size(),
                ranked.size(),
                recommended.id(),
                result.coverage().level().wireName());
        return result;
    }

    private void validate(RouteQuery query) {
        if (!region.contains(query.origin().lat(), query.origin().lon())
                || !region.contains(query.destination().lat(), query.destination().lon())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_REGION, "Origin or destination is outside the supported region.");
        }
        if (query.origin().distanceMTo(query.destination()) < properties.minOriginDestinationM()) {
            String message = "must be at least " + Math.round(properties.minOriginDestinationM()) + " m from origin";
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "One or more fields are invalid.",
                    Map.of("errors", List.of(Map.of("field", "destination", "message", message))));
        }
    }

    private List<RouteOption> requestOptions(RouteQuery query) {
        ValhallaCosting costing = ValhallaCosting.forVehicleCode(query.vehicleType().code());
        List<RouteOption> options;
        try {
            options = router.route(
                    query.origin().lat(),
                    query.origin().lon(),
                    query.destination().lat(),
                    query.destination().lon(),
                    costing,
                    properties.maxAlternates());
        } catch (ValhallaUnavailableException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Routing is temporarily unavailable.");
        }
        if (options.isEmpty()) {
            throw new ApiException(ErrorCode.NO_ROUTE, "No route found between the points.");
        }
        return options;
    }

    private CandidateScore score(RouteOption option, List<LatLon> points, Instant now) {
        String wkt = RouteWkt.lineString(points);
        List<Long> wayIds = option.wayIds();
        List<HazardNearRoute> hazards =
                repository.hazardsNear(wkt, wayIds, properties.hazardBufferM(), properties.minHazardConfidence());
        // Without way IDs (trace_attributes off or failed) a tighter buffer limits picking up parallel roads; some
        // noise remains and is accepted.
        double segmentBufferM =
                wayIds.isEmpty() ? properties.segmentBufferWithoutWaysM() : properties.segmentBufferWithWaysM();
        List<SegmentNearRoute> segments = repository.segmentsNear(wkt, wayIds, segmentBufferM);
        return scorer.score(hazards, segments, option.distanceM(), now);
    }

    private static List<LatLon> decode(RouteOption option) {
        try {
            return Polyline.decode6(option.encodedPolyline6());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping a route candidate with an undecodable shape");
            return List.of();
        }
    }
}
