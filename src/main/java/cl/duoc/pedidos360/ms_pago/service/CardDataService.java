package cl.duoc.pedidos360.ms_pago.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Transforma el número de tarjeta en datos que SÍ se pueden guardar:
 *  - cardHash: SHA-256 en hexadecimal (identifica la tarjeta sin poder recuperarla)
 *  - last4: últimos 4 dígitos (para mostrar "**** 1234")
 * El número completo nunca sale de este método ni se loguea.
 */
@Service
public class CardDataService {

    public String hash(String cardNumber) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(normalizar(cardNumber).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 viene en todas las JVM; si falta, es un error de plataforma
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public String last4(String cardNumber) {
        String digitos = normalizar(cardNumber);
        if (digitos.length() < 4) {
            throw new IllegalArgumentException("Número de tarjeta demasiado corto");
        }
        return digitos.substring(digitos.length() - 4);
    }

    /** Quita espacios y guiones para que "4111 1111..." y "41111111..." den el mismo hash */
    private String normalizar(String cardNumber) {
        if (cardNumber == null) {
            throw new IllegalArgumentException("Número de tarjeta vacío");
        }
        return cardNumber.replaceAll("[\\s-]", "");
    }
}
