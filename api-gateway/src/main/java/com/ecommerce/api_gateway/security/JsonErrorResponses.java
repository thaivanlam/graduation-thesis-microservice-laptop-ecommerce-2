package com.ecommerce.api_gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * The gateway's error envelope, in one place.
 *
 * <p>Both the SPA and the suites under {@code tests/} read {@code {"error": "..."}} out of
 * a 401 or a 403, so the bodies Spring Security produces have to look exactly like the
 * bodies {@link AuthenticationFilter} produced before ADR-0012. The three messages below
 * are the three that filter emits; they are asserted on, not decorative.</p>
 */
final class JsonErrorResponses {

    private JsonErrorResponses() {
    }

    static ServerAuthenticationEntryPoint entryPoint(String message) {
        return (exchange, ex) -> write(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    static ServerAccessDeniedHandler accessDeniedHandler(String message) {
        return (exchange, denied) -> write(exchange, HttpStatus.FORBIDDEN, message);
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
