package com.mapmyroad.backend.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.api.error.ApiExceptionHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GzipRequestFilterTest {

    private static final int LIMIT = 1024;

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EchoController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new GzipRequestFilter(LIMIT))
            .build();

    @Test
    void smallGzipBodyIsDecompressedAndHeadersHidden() throws Exception {
        byte[] body = "a".repeat(800).getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/v1/echo")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(gzip(body)))
                .andExpect(status().isOk())
                .andExpect(content().string("800|null|-1"));
    }

    @Test
    void gzipJsonIsReadable() throws Exception {
        mvc.perform(post("/v1/json")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gzip("{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").value(2));
    }

    @Test
    void gzipBombIsRejectedWith413() throws Exception {
        // 50x the limit once inflated, but small enough on the wire to pass the Content-Length check.
        byte[] smallBomb = gzip(new byte[LIMIT * 50]);
        assertThat(smallBomb.length).isLessThan(LIMIT);
        mvc.perform(post("/v1/echo")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(smallBomb))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void gzipBombIsRejectedForJsonBodies() throws Exception {
        byte[] json = ("{\"a\":\"" + "x".repeat(LIMIT * 50) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/v1/json")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gzip(json)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void oversizedPlainBodyIsRejectedByContentLength() throws Exception {
        mvc.perform(post("/v1/echo").contentType(MediaType.TEXT_PLAIN).content(new byte[LIMIT + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void plainBodyAtLimitPasses() throws Exception {
        mvc.perform(post("/v1/echo").contentType(MediaType.TEXT_PLAIN).content("b".repeat(LIMIT)))
                .andExpect(status().isOk());
    }

    @Test
    void corruptGzipIsMalformed() throws Exception {
        mvc.perform(post("/v1/json")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not gzip at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedEncodingIsRejected() throws Exception {
        mvc.perform(post("/v1/echo").header("Content-Encoding", "br").content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void chunkedPlainBodyIsCountedWhileRead() throws IOException {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/v1/echo");
        raw.setContent(new byte[LIMIT + 10]);
        LimitedBodyRequest limited = new LimitedBodyRequest(raw, false, LIMIT);
        assertThatThrownBy(() -> limited.getInputStream().readAllBytes()).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void limitedStreamReadsExactlyTheLimit() throws IOException {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/v1/echo");
        raw.setContent(gzip(new byte[LIMIT]));
        LimitedBodyRequest limited = new LimitedBodyRequest(raw, true, LIMIT);
        assertThat(limited.getInputStream().readAllBytes().length).isEqualTo(LIMIT);
        assertThat(limited.getInputStream().isFinished()).isTrue();
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
