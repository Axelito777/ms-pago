package cl.duoc.pedidos360.ms_pago.security;

import cl.duoc.pedidos360.ms_pago.exception.BadRequestException;
import cl.duoc.pedidos360.ms_pago.exception.ForbiddenException;
import cl.duoc.pedidos360.ms_pago.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Lee claims del JWT parseando el payload a mano (la firma ya la validó el API Gateway),
 * igual que extraerClaim de OrderController. Usa Jackson porque "roles" es una lista.
 *
 * Reglas:
 *  - Sin header Authorization: se permite (se usa el userId del body/query).
 *  - Header presente pero ilegible o sin "oid": 401.
 *  - userId distinto al oid del token: solo con rol Admin, si no 403.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtClaims {

    public static final String ROL_ADMIN = "Admin";

    private final ObjectMapper objectMapper;

    /** Devuelve el claim como texto, o null si no hay token o el claim no existe */
    public String extraerClaim(String authHeader, String claim) {
        JsonNode payload = leerPayload(authHeader);
        if (payload == null) {
            return null;
        }
        JsonNode valor = payload.get(claim);
        return (valor != null && valor.isString()) ? valor.asString() : null;
    }

    /** Roles del token. Si no hay token, el claim no existe o no es una lista de textos: lista vacía */
    public List<String> extraerRoles(String authHeader) {
        List<String> roles = new ArrayList<>();
        JsonNode payload = leerPayload(authHeader);
        JsonNode nodo = payload == null ? null : payload.get("roles");
        if (nodo == null) {
            return roles;
        }
        if (nodo.isArray()) {
            for (JsonNode rol : nodo) {
                if (rol.isString()) {
                    roles.add(rol.asString());
                }
            }
        } else if (nodo.isString()) {
            roles.add(nodo.asString());
        }
        return roles;
    }

    public boolean esAdmin(String authHeader) {
        return extraerRoles(authHeader).contains(ROL_ADMIN);
    }

    /** Sin token -> 401; token sin rol Admin -> 403 */
    public void exigirAdmin(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new UnauthorizedException("Falta el header Authorization");
        }
        if (!esAdmin(authHeader)) {
            throw new ForbiddenException("Se requiere el rol '" + ROL_ADMIN + "'");
        }
    }

    /**
     * Decide el userId de la operación:
     *  - con token: el oid; otro userId solo si es Admin (si no, 403)
     *  - sin token: el userId solicitado; si no viene, 400
     */
    public String resolverUserId(String authHeader, String userIdSolicitado) {
        boolean solicitadoVacio = userIdSolicitado == null || userIdSolicitado.isBlank();
        String oid = oidObligatorio(authHeader);

        if (oid == null) { // sin token
            if (solicitadoVacio) {
                throw new BadRequestException("No se pudo determinar el usuario: envía un token o el userId");
            }
            return userIdSolicitado;
        }
        if (solicitadoVacio || oid.equals(userIdSolicitado)) {
            return oid;
        }
        if (esAdmin(authHeader)) {
            return userIdSolicitado;
        }
        throw new ForbiddenException("Solo un Admin puede operar con un userId distinto al de su token");
    }

    /** Verifica que quien llama sea el dueño del recurso o Admin. Sin token se permite. */
    public void verificarAcceso(String authHeader, String userIdDueno) {
        String oid = oidObligatorio(authHeader);
        if (oid == null || oid.equals(userIdDueno) || esAdmin(authHeader)) {
            return;
        }
        throw new ForbiddenException("No tienes permiso para ver pagos de otro usuario");
    }

    /** null si no hay token; 401 si hay token pero no trae oid */
    private String oidObligatorio(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String oid = extraerClaim(authHeader, "oid");
        if (oid == null) {
            throw new UnauthorizedException("El token no contiene el claim 'oid'");
        }
        return oid;
    }

    /** Decodifica el payload del JWT. null si no hay header; 401 si es ilegible. */
    private JsonNode leerPayload(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        if (!authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("El header Authorization debe tener el formato 'Bearer <token>'");
        }
        try {
            String token = authHeader.substring(7).trim();
            String[] partes = token.split("\\.");
            String payload = partes[1];

            int resto = payload.length() % 4;
            if (resto == 2) payload += "==";
            else if (resto == 3) payload += "=";

            String payloadJson = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            JsonNode nodo = objectMapper.readTree(payloadJson);
            if (nodo == null || !nodo.isObject()) {
                throw new IllegalArgumentException("payload no es un objeto JSON");
            }
            return nodo;
        } catch (Exception e) {
            log.warn("No se pudo leer el token: {}", e.getMessage());
            throw new UnauthorizedException("Token JWT ilegible");
        }
    }
}
