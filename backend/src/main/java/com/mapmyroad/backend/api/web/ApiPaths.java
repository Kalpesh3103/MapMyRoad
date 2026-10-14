package com.mapmyroad.backend.api.web;

import jakarta.servlet.http.HttpServletRequest;

/** Path helpers shared by the web filters. */
final class ApiPaths {

    static final String API_PREFIX = "/v1/";

    private ApiPaths() {}

    /** Path inside the application, without the context path. */
    static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) {
            return "";
        }
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }

    /** True for the public API ({@code /v1/**}). */
    static boolean isApi(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return path.startsWith(API_PREFIX) || path.equals("/v1");
    }
}
