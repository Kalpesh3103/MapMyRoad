package com.mapmyroad.backend.api.tiles;

import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.config.TileProperties;
import com.mapmyroad.backend.service.tiles.TileCoord;
import com.mapmyroad.backend.service.tiles.TileEtag;
import com.mapmyroad.backend.service.tiles.TileJson;
import com.mapmyroad.backend.service.tiles.TileJsonService;
import com.mapmyroad.backend.service.tiles.TileResult;
import com.mapmyroad.backend.service.tiles.TileService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public vector tiles and TileJSON (API_SPEC.md 5.4, M4-11). Rate limited per IP. Responses are cacheable by
 * clients and a future CDN ({@code Cache-Control: public}), with a weak ETag for conditional requests.
 */
@RestController
@RequestMapping("/v1/tiles")
public class TileController {

    public static final MediaType MVT = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final TileService tileService;
    private final TileJsonService tileJsonService;
    private final CacheControl cacheControl;

    public TileController(TileService tileService, TileJsonService tileJsonService, TileProperties properties) {
        this.tileService = tileService;
        this.tileJsonService = tileJsonService;
        this.cacheControl = CacheControl.maxAge(Duration.ofSeconds(properties.cacheMaxAgeSeconds())).cachePublic();
    }

    @GetMapping("/{z}/{x}/{y}.mvt")
    @RateLimited(RateLimitPolicy.TILES)
    public ResponseEntity<byte[]> tile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        TileResult tile = tileService.tile(TileCoord.of(z, x, y));
        if (tile.isEmpty()) {
            return ResponseEntity.noContent().cacheControl(cacheControl).build();
        }
        if (TileEtag.matches(ifNoneMatch, tile.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(cacheControl)
                    .eTag(tile.etag())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MVT)
                .cacheControl(cacheControl)
                .eTag(tile.etag())
                .body(tile.bytes());
    }

    @GetMapping(value = "/tilejson.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.TILES)
    public ResponseEntity<TileJson> tileJson() {
        return ResponseEntity.ok().cacheControl(cacheControl).body(tileJsonService.tileJson());
    }
}
