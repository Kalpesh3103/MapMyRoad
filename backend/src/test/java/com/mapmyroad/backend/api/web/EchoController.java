package com.mapmyroad.backend.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Minimal controller for filter tests run through standalone MockMvc. */
@RestController
class EchoController {

    @GetMapping({"/v1/ping", "/other/ping"})
    String ping() {
        return "pong";
    }

    @GetMapping("/v1/cached")
    ResponseEntity<String> cached() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body("c");
    }

    /** Echoes the body length and whether the compression headers are still visible to the handler. */
    @PostMapping("/v1/echo")
    String echo(@RequestBody String body, HttpServletRequest request) {
        return body.length() + "|" + request.getHeader("Content-Encoding") + "|" + request.getContentLength();
    }

    @PostMapping("/v1/json")
    Map<String, Object> json(@RequestBody Map<String, Object> body) {
        return Map.of("keys", body.size());
    }
}
