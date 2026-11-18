package cl.duoc.pedidos360.ms_pago.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardDataServiceTest {

    private final CardDataService service = new CardDataService();

    @Test
    void hash_esSha256HexConocido() {
        // echo -n 4111111111111111 | sha256sum
        assertThat(service.hash("4111111111111111"))
                .isEqualTo("9bbef19476623ca56c17da75fd57734dbf82530686043a6e491c6d71befe8f6e");
    }

    @Test
    void hash_esDeterministicoYNoContieneElNumero() {
        String h1 = service.hash("4111111111111111");
        String h2 = service.hash("4111111111111111");

        assertThat(h1).isEqualTo(h2).hasSize(64).doesNotContain("4111111111111111");
    }

    @Test
    void hash_tarjetasDistintasDanHashDistinto() {
        assertThat(service.hash("4111111111111111")).isNotEqualTo(service.hash("5555555555554444"));
    }

    @Test
    void hash_ignoraEspaciosYGuiones() {
        assertThat(service.hash("4111 1111-1111 1111")).isEqualTo(service.hash("4111111111111111"));
    }

    @Test
    void last4_devuelveUltimosCuatro() {
        assertThat(service.last4("4111111111111234")).isEqualTo("1234");
        assertThat(service.last4("4000000000000000")).isEqualTo("0000");
    }

    @Test
    void last4_numeroMuyCorto_falla() {
        assertThatThrownBy(() -> service.last4("123")).isInstanceOf(IllegalArgumentException.class);
    }
}
