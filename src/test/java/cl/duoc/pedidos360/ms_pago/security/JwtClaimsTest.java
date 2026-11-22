package cl.duoc.pedidos360.ms_pago.security;

import cl.duoc.pedidos360.ms_pago.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas de JwtClaims cuando no hay token. Por HTTP ya no se llega aquí sin token
 * (SecurityConfig responde 401), pero la lógica se mantiene y se prueba directo.
 * Antes la cubrían sinToken_usaUserIdDelBody, sinTokenNiUserId_400 y listarSinUserIdNiToken_400.
 */
class JwtClaimsTest {

    private final JwtClaims jwtClaims = new JwtClaims(JsonMapper.builder().build());

    @Test
    void sinToken_usaUserIdSolicitado() {
        assertThat(jwtClaims.resolverUserId(null, "user-body")).isEqualTo("user-body");
    }

    @Test
    void sinTokenNiUserId_badRequest() {
        assertThatThrownBy(() -> jwtClaims.resolverUserId(null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void sinToken_userIdEnBlanco_badRequest() {
        assertThatThrownBy(() -> jwtClaims.resolverUserId(null, "  "))
                .isInstanceOf(BadRequestException.class);
    }
}
