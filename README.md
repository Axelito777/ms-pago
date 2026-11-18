# ms-pago (puerto 8087)

Recibe intentos de pago, aplica un **antifraude por ventana deslizante** y publica **cada intento**
(aprobado, rechazado o bloqueado) en el tópico Kafka `payment-attempts`. Un consumidor del mismo
servicio (`payment-audit-group`) lee ese tópico y guarda una auditoría.

```
POST /payments ─► validar body ─► userId (token/body) ─► cardHash + last4 ─► antifraude
                                                                           │
                                       bloqueado ◄─(>3 en 10 s)────────────┤
                                       (FraudAlert, 429)                   └─► PaymentGateway (201 / 402)
                                                  │                                 │
                                                  └──────► guardar Payment ◄────────┘
                                                                 │
                                                  Kafka: payment-attempts (key = cardHash)
                                                                 │
                                                  @KafkaListener payment-audit-group ─► PaymentAuditLog
```

## Datos de tarjeta
- **Nunca** se guardan ni se loguean el número de tarjeta ni el CVV.
- Se guarda `cardHash` (SHA-256 del número) y `last4`.
- `cardNumber` y `cvv` no aparecen en el `toString()` del request, y los errores de validación no repiten
  el valor recibido.

## Endpoints

### POST /payments
```json
{
  "orderId": 10,
  "userId": "opcional-solo-sin-token",
  "cardNumber": "4111111111111111",
  "cardHolder": "Juan Perez",
  "expiry": "12/28",
  "cvv": "123",
  "amount": 25990,
  "currency": "CLP"
}
```
| Campo | Regla |
|---|---|
| `orderId` | obligatorio, positivo |
| `cardNumber` | 13 a 19 dígitos, sin espacios |
| `cvv` | 3 o 4 dígitos |
| `expiry` | `MM/YY`, no vencida (vale todo el mes de vencimiento) |
| `amount` | mayor que 0 |
| `currency` | 3 letras mayúsculas; si no viene, `CLP` |

**Respuestas:** `201` aprobado · `402` rechazado por la pasarela · `429` bloqueado por fraude ·
`400` validación (no cuenta en la ventana antifraude) · `401` token ilegible · `403` userId ajeno sin Admin.

Para probar: tarjeta que termina en `0000` (ej. `4000000000000000`) → **402**.
La misma tarjeta enviada **4 veces en menos de 10 s** → la 4.ª da **429**.

### GET /payments/{id}
Detalle de un pago. Con token, solo el dueño o un Admin (si no, 403). Inexistente → 404.

### GET /payments?userId=...
Pagos del usuario. Sin `userId` se usa el `oid` del token; si no hay ninguno → 400.
Un `userId` distinto al `oid` del token solo se acepta con rol **Admin** (si no, 403).

### GET /payments/fraud-alerts
Lista de alertas (`cardHash`, `last4`, `userId`, `cantidadIntentos`, `fecha`, `motivo`). **Solo Admin**:
sin token → 401, sin rol Admin → 403.

## ¿Cómo se identifica al usuario?
El API Gateway valida el JWT. Este servicio lee el payload a mano (`JwtClaims`, mismo enfoque que
`extraerClaim` de `OrderController`):
- `oid` del token = usuario real. Si no hay token, se usa el `userId` del body o del query.
- `roles` debe contener `Admin` para ver alertas u operar con otro `userId`. Si `roles` no existe o no es
  una lista, se trata como vacío.

## Antifraude (ventana deslizante)
Por cada `cardHash` se guardan los instantes de los intentos recientes. En cada intento se descartan los
de hace 10 s o más, se agrega el actual y, si quedan **más de 3**, se bloquea (`BLOCKED_FRAUD`): se crea
una `FraudAlert`, se loguea una advertencia y **no se llama a la pasarela**.
- Los intentos bloqueados **también cuentan**: si se sigue insistiendo, se sigue bloqueando.
- Los 400 por validación **no** cuentan (se rechazan antes de llegar al antifraude).
- Una tarea periódica elimina de memoria las tarjetas sin intentos recientes.

**Limitación:** la ventana vive **en memoria** (`ConcurrentHashMap`). Se pierde al reiniciar y no se
comparte si hay varias instancias del servicio. En producción se usaría Redis o una consulta a BD.

## Pasarela
`PaymentGateway` es una interfaz; hoy la implementa `SimulatedPaymentGateway` (rechaza si `last4` es
`0000`). Para Webpay se agrega otra implementación sin tocar el resto.

## Kafka
- Tópico `payment-attempts` (lo crea `kafka-infra/create-topics.sh`; este servicio no crea tópicos).
- **Key = cardHash**: los intentos de una misma tarjeta caen siempre en la misma partición, y Kafka
  mantiene el orden dentro de cada partición.
- Productor: `acks=all`, idempotente, JSON sin headers de tipo, `max.block.ms` corto.
- **Si Kafka está caído, el pago igual responde**: el error de publicación solo se loguea.
- Consumidor `payment-audit-group`: `earliest`, `AckMode.RECORD`, `ErrorHandlingDeserializer`; tras 2
  reintentos (1 s) el mensaje se loguea y se descarta (sin DLT). La auditoría es idempotente por `eventId`.

Evento publicado:
```json
{ "eventId": "uuid", "paymentId": 1, "orderId": 10, "userId": "oid", "cardHash": "9bbe...",
  "last4": "1111", "amount": 25990, "currency": "CLP", "status": "APPROVED",
  "fraudSuspected": false, "timestamp": "2026-11-16T15:00:00Z" }
```

## Variables de entorno
| Variable | Por defecto | Descripción |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers Kafka |
| `KAFKA_MAX_BLOCK_MS` | `3000` | Espera máxima al publicar |
| `KAFKA_LISTENER_AUTO_STARTUP` | `true` | Arrancar el consumidor de auditoría |
| `FRAUD_WINDOW_SECONDS` | `10` | Tamaño de la ventana |
| `FRAUD_MAX_ATTEMPTS` | `3` | Intentos permitidos en la ventana |
| `FRAUD_CLEANUP_INTERVAL_MS` | `60000` | Cada cuánto se limpia la memoria |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / vacío | H2 |
| `SPRING_PROFILES_ACTIVE` | — | `prod` en el servidor |

## Cómo probarlo
```bash
mvn clean package          # 50 tests, sin broker (perfil test: listener apagado)
docker build -t ms-pago .
docker run -d --name ms-pago -p 8087:8087 --env-file .env ms-pago
```
Demo de fraude (fish o bash), sin token y con `userId` en el body:
```bash
for i in 1 2 3 4
  curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8087/payments \
    -H 'Content-Type: application/json' \
    -d '{"orderId":1,"userId":"demo","cardNumber":"4111111111111111","cardHolder":"Demo","expiry":"12/30","cvv":"123","amount":1000}'
end
# 201 201 201 429   (en bash: for i in 1 2 3 4; do ...; done)
```
Luego `GET /payments/fraud-alerts` con un token Admin, y en Kafka UI revisa el tópico `payment-attempts`:
los 4 mensajes quedan en la **misma partición**.
