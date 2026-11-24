package cl.duoc.pedidos360.ms_pago.service.audit;

import cl.duoc.pedidos360.ms_pago.dto.PaymentAuditResponse;
import cl.duoc.pedidos360.ms_pago.event.PaymentAttemptEvent;
import cl.duoc.pedidos360.ms_pago.exception.AuditStoreUnavailableException;
import cl.duoc.pedidos360.ms_pago.model.PaymentMethod;
import cl.duoc.pedidos360.ms_pago.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Store real con un DynamoDbClient simulado (Mockito): sin red ni AWS.
 * Se revisa lo que de verdad se enviaría a DynamoDB (PutItemRequest / QueryRequest).
 */
class DynamoPaymentAuditStoreTest {

    private static final String TABLA = "tabla-test";

    private DynamoDbClient cliente;
    private DynamoPaymentAuditStore store;

    @BeforeEach
    void setUp() {
        cliente = mock(DynamoDbClient.class);
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(cliente).build();
        store = new DynamoPaymentAuditStore(enhanced, TABLA);
    }

    private static PaymentAttemptEvent evento(Instant fecha) {
        return new PaymentAttemptEvent("ev-1", 7L, 55L, "oid-123", "hash-secreto", "6623",
                new BigDecimal("25990"), "CLP", PaymentStatus.APPROVED, false, fecha, PaymentMethod.WEBPAY);
    }

    // ---------- save ----------

    @Test
    void save_armaUserIdYSkConFechaDeLargoFijo() {
        when(cliente.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        // Milisegundos en cero: Instant.toString() daría "...:09Z"; el sk debe llevar ".000Z"
        store.save(evento(Instant.parse("2026-11-23T14:05:09Z")));

        Map<String, AttributeValue> item = itemGuardado();
        assertThat(item.get("userId").s()).isEqualTo("oid-123");
        assertThat(item.get("sk").s()).isEqualTo("2026-11-23T14:05:09.000Z#7#APPROVED");
    }

    @Test
    void save_guardaLosCamposDelEventoPeroNoLosProhibidos() {
        when(cliente.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        store.save(evento(Instant.parse("2026-11-23T14:05:09.123Z")));

        Map<String, AttributeValue> item = itemGuardado();
        assertThat(item).containsOnlyKeys("userId", "sk", "eventId", "paymentId", "orderId", "last4",
                "amount", "currency", "status", "method", "fraudSuspected", "timestamp");
        assertThat(item).doesNotContainKeys("cardHash", "cardNumber", "cvv", "token", "webpayToken");
        assertThat(item.values()).noneMatch(v -> "hash-secreto".equals(v.s()));
        assertThat(item.get("last4").s()).isEqualTo("6623");
        assertThat(item.get("amount").n()).isEqualTo("25990");
        assertThat(item.get("method").s()).isEqualTo("WEBPAY");
        assertThat(item.get("fraudSuspected").bool()).isFalse();
    }

    @Test
    void sk_ordenAlfabeticoIgualAlCronologico() {
        String antes = DynamoPaymentAuditStore.construirSk(Instant.parse("2026-11-23T14:05:09.500Z"), 1L, "APPROVED");
        String despues = DynamoPaymentAuditStore.construirSk(Instant.parse("2026-11-23T14:05:10Z"), 1L, "APPROVED");
        assertThat(antes).isLessThan(despues);
    }

    @Test
    void save_eventoSinUserId_lanzaExcepcion() {
        PaymentAttemptEvent sinUsuario = new PaymentAttemptEvent("e", 1L, 1L, null, null, "1111",
                BigDecimal.ONE, "CLP", PaymentStatus.APPROVED, false, Instant.now(), PaymentMethod.SIMULATED);
        assertThatThrownBy(() -> store.save(sinUsuario)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- findByUser ----------

    @Test
    void findByUser_consultaSoloEseUsuarioDescendenteYConLimite() {
        Map<String, AttributeValue> fila = Map.of(
                "userId", AttributeValue.fromS("oid-123"),
                "sk", AttributeValue.fromS("2026-11-23T14:05:09.000Z#7#APPROVED"),
                "paymentId", AttributeValue.fromN("7"),
                "last4", AttributeValue.fromS("6623"),
                "amount", AttributeValue.fromN("25990"),
                "status", AttributeValue.fromS("APPROVED"),
                "timestamp", AttributeValue.fromS("2026-11-23T14:05:09Z"));
        simularQuery(QueryResponse.builder().items(fila, fila, fila).build());

        List<PaymentAuditResponse> resultado = store.findByUser("oid-123", 2);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(cliente).query(captor.capture());
        QueryRequest consulta = captor.getValue();
        assertThat(consulta.tableName()).isEqualTo(TABLA);
        assertThat(consulta.scanIndexForward()).isFalse();
        assertThat(consulta.limit()).isEqualTo(2);
        assertThat(consulta.expressionAttributeValues().values()).containsExactly(AttributeValue.fromS("oid-123"));

        assertThat(resultado).hasSize(2); // aunque DynamoDB devolviera más, se corta en el límite
        assertThat(resultado.get(0).paymentId()).isEqualTo(7L);
        assertThat(resultado.get(0).last4()).isEqualTo("6623");
        assertThat(resultado.get(0).timestamp()).isEqualTo(Instant.parse("2026-11-23T14:05:09Z"));
    }

    @Test
    void findByUser_siDynamoFalla_503() {
        when(cliente.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(cliente, inv.getArgument(0)));
        when(cliente.query(any(QueryRequest.class))).thenThrow(DynamoDbException.builder().message("caído").build());

        assertThatThrownBy(() -> store.findByUser("oid-123", 20))
                .isInstanceOf(AuditStoreUnavailableException.class);
    }

    // ---------- helpers ----------

    private Map<String, AttributeValue> itemGuardado() {
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(cliente).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo(TABLA);
        return captor.getValue().item();
    }

    /** El cliente enhanced pagina con queryPaginator, que a su vez llama a query() */
    private void simularQuery(QueryResponse respuesta) {
        when(cliente.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(cliente, inv.getArgument(0)));
        when(cliente.query(any(QueryRequest.class))).thenReturn(respuesta);
    }
}
