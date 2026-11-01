package com.lld.app.gateway;

import com.lld.gateway.Gateway;
import com.lld.gateway.GatewayRequest;
import com.lld.gateway.GatewayResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A thin adapter: turns any HTTP request under {@code /gw/} into a {@link GatewayRequest}, runs the core pipeline, and turns the answer back into HTTP. All decisions (401, 429, 503, ...) live in the core. */
@RestController
class GatewayController {

    /** Headers that describe one network hop, not the request (RFC 9110 section 7.6.1). A proxy must not pass them on. */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection");

    private final Gateway gateway;
    private final int maxBodyBytes;

    /** Creates the controller around the gateway bean and the body-size cap. */
    GatewayController(Gateway gateway, GatewayProperties props) {
        this.gateway = gateway;
        this.maxBodyBytes = props.maxBodyBytes();
    }

    /**
     * Forwards one request through the gateway for every method and every path under {@code /gw/}.
     *
     * <p>The core already turns every expected failure into a status code. The catch below covers only the core call, so a programming error in the core becomes a plain 500 here without any global handler touching other controllers.
     */
    @RequestMapping("/gw/**")
    ResponseEntity<String> forward(HttpServletRequest http) throws IOException {
        GatewayRequest request = toCore(http);
        GatewayResponse response;
        try {
            response = gateway.handle(request);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("gateway error");
        }
        ResponseEntity.BodyBuilder out = ResponseEntity.status(response.status());
        response.headers().forEach(out::header);
        return out.body(response.body());
    }

    /**
     * Copies method, path (without the {@code /gw} prefix), query, headers and body into the core request type.
     *
     * <p>Hop-by-hop headers, and any header named by the {@code Connection} header, are dropped. A header sent several times keeps only its first value (documented limit: the core request has one value per name). The body is read only up to the cap plus one byte, so an endless upload cannot fill memory; the core then answers 413 when the cap is passed.
     */
    private GatewayRequest toCore(HttpServletRequest http) throws IOException {
        String path = http.getRequestURI().substring(http.getContextPath().length() + "/gw".length());
        Set<String> drop = new HashSet<>(HOP_BY_HOP);
        String connection = http.getHeader("Connection");
        if (connection != null) {
            for (String token : connection.split(",")) {
                drop.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(http.getHeaderNames())) {
            if (!drop.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, http.getHeader(name));
            }
        }
        String body;
        try (InputStream in = http.getInputStream()) {
            body = new String(in.readNBytes(maxBodyBytes + 1), StandardCharsets.UTF_8);
        }
        return new GatewayRequest(http.getMethod(), path, headers, body, Map.of(), http.getQueryString());
    }
}
