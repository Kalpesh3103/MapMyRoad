package com.mapmyroad.backend.integration.photon;

import com.mapmyroad.backend.config.GeocodeProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Photon over HTTP: {@code GET {baseUrl}/api} and {@code GET {baseUrl}/reverse}.
 *
 * <p>SSRF (SECURITY.md T12): the scheme, host and path come only from {@link GeocodeProperties#baseUrl()}. Every
 * request value is a URI template variable, which Spring encodes strictly (everything outside the unreserved set,
 * including {@code & = + / ? #}), so search text cannot add parameters or change the target.
 *
 * <p>A semaphore caps concurrent upstream calls ({@code upstream-max-concurrent}) to respect the public instance's
 * fair use; a request that cannot get a slot within {@code upstream-queue-timeout} fails as BUSY rather than
 * queueing without bound.
 *
 * <p>Logs carry HTTP status codes and exception types only, never the query, coordinates or response body.
 */
@Component
public class HttpPhotonClient implements PhotonClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPhotonClient.class);

    private final RestClient restClient;
    private final GeocodeProperties properties;
    private final Semaphore slots;

    public HttpPhotonClient(RestClient.Builder restClientBuilder, GeocodeProperties properties) {
        this.properties = properties;
        this.slots = new Semaphore(properties.upstreamMaxConcurrent(), true);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        // clone(): never mutate the shared builder (the Valhalla clients get one too).
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<PhotonPlace> search(PhotonSearchRequest request) {
        return PhotonResponseParser.parse(call(searchUri(request)));
    }

    @Override
    public List<PhotonPlace> reverse(PhotonReverseRequest request) {
        List<PhotonPlace> places = PhotonResponseParser.parse(call(reverseUri(request)));
        return places.isEmpty() ? places : List.of(places.get(0));
    }

    URI searchUri(PhotonSearchRequest request) {
        UriComponentsBuilder builder = base("/api").queryParam("q", "{q}").queryParam("limit", "{limit}");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("q", request.query());
        values.put("limit", request.limit());
        if (request.lang() != null) {
            builder.queryParam("lang", "{lang}");
            values.put("lang", request.lang());
        }
        if (request.biasLat() != null && request.biasLon() != null) {
            builder.queryParam("lat", "{lat}").queryParam("lon", "{lon}");
            values.put("lat", plain(request.biasLat()));
            values.put("lon", plain(request.biasLon()));
        }
        SupportedRegion box = request.restrictTo();
        if (box != null) {
            // Photon's bbox order is minLon,minLat,maxLon,maxLat, the same as SupportedRegion.
            builder.queryParam("bbox", "{bbox}");
            values.put("bbox", String.join(",",
                    plain(box.minLon()), plain(box.minLat()), plain(box.maxLon()), plain(box.maxLat())));
        }
        return builder.encode().buildAndExpand(values).toUri();
    }

    URI reverseUri(PhotonReverseRequest request) {
        UriComponentsBuilder builder = base("/reverse").queryParam("lat", "{lat}").queryParam("lon", "{lon}");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lat", plain(request.lat()));
        values.put("lon", plain(request.lon()));
        if (request.lang() != null) {
            builder.queryParam("lang", "{lang}");
            values.put("lang", request.lang());
        }
        return builder.encode().buildAndExpand(values).toUri();
    }

    private UriComponentsBuilder base(String path) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl()).path(path);
    }

    private String call(URI uri) {
        acquireSlot();
        try {
            return restClient.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Photon answered HTTP {}", status);
            PhotonUnavailableException.Reason reason = status == 429
                    ? PhotonUnavailableException.Reason.RATE_LIMITED
                    : PhotonUnavailableException.Reason.HTTP_ERROR;
            throw new PhotonUnavailableException(reason, "Photon answered HTTP " + status, e);
        } catch (RestClientException e) {
            boolean timeout = hasCause(e, HttpTimeoutException.class) || hasCause(e, SocketTimeoutException.class);
            log.warn("Photon unreachable: {}", timeout ? "timeout" : e.getClass().getSimpleName());
            throw new PhotonUnavailableException(
                    timeout ? PhotonUnavailableException.Reason.TIMEOUT : PhotonUnavailableException.Reason.UNREACHABLE,
                    "Photon unreachable",
                    e);
        } finally {
            slots.release();
        }
    }

    private void acquireSlot() {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(properties.upstreamQueueTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhotonUnavailableException(PhotonUnavailableException.Reason.BUSY, "Interrupted", e);
        }
        if (!acquired) {
            throw new PhotonUnavailableException(PhotonUnavailableException.Reason.BUSY, "All Photon slots busy");
        }
    }

    /** Plain decimal notation: {@code Double.toString} would write small values as {@code 1.0E-4}. */
    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
