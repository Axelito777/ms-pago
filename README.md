# ms-pago (puerto 8087)

Recibe intentos de pago, aplica un **antifraude por ventana deslizante** y publica **cada intento**
(aprobado, rechazado o bloqueado) en el tópico Kafka `payment-attempts`. Un consumidor del mismo
servicio (`payment-audit-group`) lee ese tópico y guarda una auditoría (en H2 y, opcionalmente, en
**DynamoDB**; ver [Auditoría en DynamoDB](#auditoría-en-dynamodb)).

Tiene **dos métodos de pago**: el **simulado** (`POST /payments`, con antifraude) y **Webpay Plus** de
Transbank en ambiente de integración (ver [Webpay Plus](#webpay-plus)).

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
                                                  @KafkaListener payment-audit-group ─► PaymentAuditLog (H2)
                                                                                     └─► DynamoDB (si está activo)
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

**Respuestas:** `201` aprobado · `402` rechazado por la pasarela · `429` bloqueado por fraude (y el usuario
queda bloqueado) · `423` usuario bloqueado · `400` validación (no cuenta en la ventana antifraude) · `401` token ilegible · `403` userId ajeno sin Admin.

Para probar: tarjeta que termina en `0000` (ej. `4000000000000000`) → **402**.
4 intentos del mismo usuario en menos de 10 s (con la misma tarjeta **o con tarjetas distintas**) → el 4.º da
**429** y el usuario queda bloqueado `BLOCK_MINUTES`. El 5.º da **423**:
```json
{ "error": "USER_BLOCKED", "until": "2026-11-26T16:40:00Z", "message": "Tu cuenta está bloqueada temporalmente por actividad sospechosa" }
```

### GET /payments/fraud-status/{userId}
Estado de bloqueo: `{"blocked": true, "until": "2026-11-26T16:40:00Z"}` o `{"blocked": false, "until": null}`.
Solo el dueño (`oid` = `userId`) o un Admin (si no, 403). Lo usa el frontend al recargar, y lo consultan
carrito-service (confirmar) y orders-service (crear pedido) reenviando el JWT del usuario.

### DELETE /payments/fraud-blocks/{userId}
Desbloqueo manual para la demo → `204`. Exige el rol **`Admin`** en el claim **`roles`** (valor exacto, lista o
texto), **igual que kafka-admin-service**: un mismo token Admin sirve en ambos. Sin rol → 403.
También borra el conteo del usuario en la ventana.

### GET /payments/{id}
Detalle de un pago. Con token, solo el dueño o un Admin (si no, 403). Inexistente → 404.

### GET /payments?userId=...
Pagos del usuario. Sin `userId` se usa el `oid` del token; si no hay ninguno → 400.
Un `userId` distinto al `oid` del token solo se acepta con rol **Admin** (si no, 403).

### GET /payments/fraud-alerts
Lista de alertas (`cardHash`, `last4`, `userId`, `cantidadIntentos`, `fecha`, `motivo`, `blockedUntil`). **Solo Admin**:
sin token → 401, sin rol Admin → 403.

### GET /payments/audit?limit=20
Intentos de pago del usuario del token (sacados de DynamoDB), **más recientes primero**. El usuario es
siempre el `oid` del token: no hay parámetro `userId`, así que cada uno ve solo los suyos.
`limit` por defecto 20; fuera de rango se ajusta a 1..50.
```json
[ { "eventId": "uuid", "paymentId": 7, "orderId": 55, "last4": "6623", "amount": 25990, "currency": "CLP",
    "status": "APPROVED", "method": "WEBPAY", "fraudSuspected": false, "timestamp": "2026-11-23T14:05:09Z" } ]
```
**Respuestas:** `200` · `401` sin token / token inválido / sin `oid` · `403` sin `payments.read` ·
`503` DynamoDB desactivado (`DYNAMODB_ENABLED=false`) o sin respuesta (mensajes distintos).

## Seguridad (JWT de Azure Entra ID)
El servicio valida el token por su cuenta, además del API Gateway (`config/SecurityConfig`):
- **Firma** con las llaves públicas de Azure (JWKS; se descargan con el primer token, no al arrancar).
- **Emisor** `https://login.microsoftonline.com/<tenant>/v2.0`, **audiencia** `api://<client-id>` o `<client-id>`
  y **expiración** (60 s de tolerancia).
- `scp` → `SCOPE_xxx`, `roles` → `ROLE_xxx`. Cualquier ruta no listada está denegada.
- **401**: sin token o token inválido (firma, emisor, audiencia, expirado). **403**: token válido sin el scope.

| Método | Ruta | Scope |
|---|---|---|
| POST | `/payments` | `payments.write` |
| GET | `/payments` | `payments.read` |
| GET | `/payments/{id}` | `payments.read` |
| GET | `/payments/audit` | `payments.read` |
| GET | `/payments/fraud-alerts` | `payments.read` |
| GET | `/payments/fraud-status/{userId}` | `payments.read` (+ dueño o Admin) |
| DELETE | `/payments/fraud-blocks/{userId}` | `payments.write` (+ rol `Admin`) |
| POST | `/payments/webpay/init` | `payments.write` |
| GET, POST | `/payments/webpay/return` | **público** (ver Webpay) |

Las reglas de dueño y rol **Admin** (abajo) se aplican después, en `JwtClaims`, y no cambiaron.

## ¿Cómo se identifica al usuario?
Con el token ya validado por `SecurityConfig`, este servicio lee el payload a mano (`JwtClaims`, mismo enfoque que
`extraerClaim` de `OrderController`):
- `oid` del token = usuario real. (La regla "sin token se usa el `userId` del body/query" sigue en `JwtClaims`,
  pero por HTTP ya no se llega sin token: responde 401.)
- `roles` debe contener `Admin` para ver alertas u operar con otro `userId`. Si `roles` no existe o no es
  una lista, se trata como vacío.

## Antifraude (ventana deslizante + bloqueo del usuario)
Cada intento se cuenta **dos veces**: por tarjeta (`card:<hash>`) y por usuario (`user:<oid>`), así cambiar de
tarjeta no evita el conteo. Por cada clave se guardan los instantes de los intentos recientes. En cada intento se descartan los
de hace 10 s o más, se agrega el actual y, si quedan **más de 3**, se bloquea (`BLOCKED_FRAUD`): se crea
una `FraudAlert`, se loguea una advertencia y **no se llama a la pasarela**.
- Los intentos bloqueados **también cuentan**: si se sigue insistiendo, se sigue bloqueando.
- Los 400 por validación **no** cuentan (se rechazan antes de llegar al antifraude).
- Una tarea periódica elimina de memoria las claves sin intentos recientes.

**Bloqueo del usuario:** el intento que da 429 bloquea al usuario (`oid`) por `BLOCK_MINUTES` (10 por defecto).
Mientras dura, `POST /payments` y `POST /payments/webpay/init` responden **423** sin llamar a la pasarela ni
guardar nada; carrito-service y orders-service consultan `fraud-status` y también responden 423. El bloqueo
expira solo, o lo quita un Admin con `DELETE /payments/fraud-blocks/{userId}`. La alerta guarda `blockedUntil`,
y el evento de ese intento en `payment-attempts` también lo lleva (es el **evento de bloqueo**; en los demás
intentos viene `null`). Webpay no suma intentos (la tarjeta no pasa por nosotros), pero respeta el bloqueo.

**Limitación:** la ventana y los bloqueos viven **en memoria** (`ConcurrentHashMap`). Se pierde al reiniciar y no se
comparte si hay varias instancias del servicio. En producción se usaría Redis o una consulta a BD.

## Pasarela
`PaymentGateway` es una interfaz; hoy la implementa `SimulatedPaymentGateway` (rechaza si `last4` es
`0000`). Webpay no usa esta interfaz porque su flujo es distinto (el usuario sale de nuestra página):
tiene su propio cliente, `WebpayClient`.

## Kafka
- Tópico `payment-attempts` (lo crea `kafka-infra/create-topics.sh`; este servicio no crea tópicos).
- **Key = cardHash** (pago simulado): los intentos de una misma tarjeta caen siempre en la misma partición,
  y Kafka mantiene el orden dentro de cada partición.
- **Key = userId** (Webpay): no conocemos la tarjeta, así que se ordena por usuario.
- Productor: `acks=all`, idempotente, JSON sin headers de tipo, `max.block.ms` corto.
- **Si Kafka está caído, el pago igual responde**: el error de publicación solo se loguea.
- Consumidor `payment-audit-group`: `earliest`, `AckMode.RECORD`, `ErrorHandlingDeserializer`; tras 2
  reintentos (1 s) el mensaje se loguea y se descarta (sin DLT). La auditoría es idempotente por `eventId`.

Evento publicado:
```json
{ "eventId": "uuid", "paymentId": 1, "orderId": 10, "userId": "oid", "cardHash": "9bbe...",
  "last4": "1111", "amount": 25990, "currency": "CLP", "status": "APPROVED",
  "fraudSuspected": false, "timestamp": "2026-11-16T15:00:00Z", "method": "SIMULATED" }
```
En Webpay: `method = "WEBPAY"`, `cardHash = null`, y `status` puede ser `APPROVED`, `REJECTED` o `CANCELLED`.

## Auditoría en DynamoDB
Además de H2 (que es en memoria y se pierde al reiniciar), el consumidor `payment-audit-group` guarda una
copia de cada intento en una tabla DynamoDB, que es persistente y administrada por AWS.

```
payment-attempts ─► PaymentAuditListener ─► PaymentAuditService ─► H2 (PaymentAuditLog)
                                                               └─► PaymentAuditStore.save()
                                                                     ├─ DynamoPaymentAuditStore (DYNAMODB_ENABLED=true)
                                                                     └─ NoOpPaymentAuditStore   (false, por defecto)
```

### Diseño de la tabla
| | Atributo | Tipo | Ejemplo |
|---|---|---|---|
| Clave de partición | `userId` | S | `oid` del usuario en Entra ID |
| Clave de orden | `sk` | S | `2026-11-23T14:05:09.000Z#7#APPROVED` |
| | `eventId`, `last4`, `currency`, `status`, `method`, `timestamp` | S | |
| | `paymentId`, `orderId`, `amount` | N | |
| | `fraudSuspected` | BOOL | |

**¿Por qué `userId` + `sk`?**
- La única consulta que hacemos es "los intentos de **este** usuario, los más recientes primero". En DynamoDB
  una consulta (`Query`) eficiente necesita la clave de partición exacta, así que esa es `userId`: todos los
  intentos de un usuario quedan juntos y se leen sin recorrer la tabla (`Scan`).
- Dentro de la partición, DynamoDB ordena por la clave de orden. Como `sk` **empieza con la fecha**, basta
  `ScanIndexForward=false` + `Limit` para traer los N más recientes.
- `paymentId` y `estado` en `sk` la hacen **única**: un mismo pago puede tener varios intentos o estados
  (ej. Webpay `PENDING` → `APPROVED`) sin pisarse. Y si Kafka entrega dos veces el mismo evento, la clave es
  idéntica y el `PutItem` reemplaza el ítem: **no se duplica**.
- La fecha va **siempre en UTC y con milisegundos** (`.000Z`): con `Instant.toString()` el largo cambia según
  los decimales y el orden alfabético dejaría de ser cronológico.

**Qué NO se guarda:** número de tarjeta, CVV, token de Webpay ni `cardHash` (un hash de tarjeta sin sal se
puede revertir probando todas las tarjetas posibles). Solo `last4`. Hay un test que lo verifica.

### Si DynamoDB falla
- **Al guardar:** se captura la excepción y se loguea solo `paymentId`, estado y tipo de error. El consumidor
  de Kafka sigue y el pago no se ve afectado (el pago ya respondió antes; esto ocurre en el consumidor).
  Cada llamada tiene un máximo de 3 s (1 s por intento) para no frenar al consumidor.
- Eventos sin `userId` no se guardan en DynamoDB (warning en el log).
- **Al consultar:** `503`.

### Crear la tabla (CLI, una vez)
El servicio **no** crea la tabla.
```bash
aws dynamodb create-table \
  --table-name pedidos360-payment-audit \
  --attribute-definitions AttributeName=userId,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=userId,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

### Credenciales y permisos
Sin claves en el código ni en properties: el SDK usa su **cadena de credenciales por defecto**. En la EC2
vienen del **perfil de instancia** (rol IAM). Permiso mínimo del rol:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["dynamodb:PutItem", "dynamodb:Query"],
    "Resource": "arn:aws:dynamodb:us-east-1:<ACCOUNT_ID>:table/pedidos360-payment-audit"
  }]
}
```
Si el contenedor no ve el rol de la instancia (IMDSv2 con límite de saltos 1), sube el límite a 2:
`aws ec2 modify-instance-metadata-options --instance-id <INSTANCE_ID> --http-put-response-hop-limit 2`.

### Probarlo
1. `DYNAMODB_ENABLED=true` en el `.env` y reiniciar el contenedor.
2. Hacer un pago (simulado o Webpay).
3. `GET /payments/audit?limit=5` con un token con `payments.read` → aparece el intento.
4. En AWS: `aws dynamodb query --table-name pedidos360-payment-audit --key-condition-expression "userId = :u" --expression-attribute-values '{":u":{"S":"<OID>"}}' --no-scan-index-forward`
5. Con `DYNAMODB_ENABLED=false`: el pago funciona igual y `GET /payments/audit` responde 503.

## Webpay Plus
Webpay **saca al usuario de nuestra página**: la tarjeta se escribe en el sitio de Transbank, nunca aquí.
El cliente (`HttpWebpayClient`) usa la API REST con `RestClient`, sin SDK.

```
Frontend ─POST /payments/webpay/init (JWT)─► ms-pago: Payment PENDING ─► Transbank: crear transacción
   ◄────────────── 201 {paymentId, token, url, buyOrder} ───────────────────────┘
Frontend ─form POST token_ws─► Webpay (el usuario paga con tarjeta de prueba)
Webpay ─redirige al navegador─► GET|POST /payments/webpay/return (SIN JWT)
   ms-pago: commit con Transbank ─► APPROVED / REJECTED  (o CANCELLED si anuló / se agotó el tiempo)
   ─► Kafka payment-attempts ─► 302 a WEBPAY_FRONTEND_RESULT_URL?paymentId=<id>&status=<ESTADO>
Frontend ─GET /payments/{id} (JWT)─► verifica el estado real y, si es APPROVED, crea la orden
```

### POST /payments/webpay/init
```json
{ "orderId": 55, "amount": 25990, "currency": "CLP" }
```
`orderId` es el `cartId` del frontend (igual que en el pago simulado). Mismas validaciones que `POST /payments`
y además: solo `CLP` y montos **enteros** (Webpay no acepta decimales) → si no, 400.
```json
{ "paymentId": 7, "token": "01ab...", "url": "https://webpay3gint.transbank.cl/webpayserver/initTransaction",
  "buyOrder": "P360-7-mhq3k2ab" }
```
**Respuestas:** `201` creado (`PENDING`) · `400` validación · `401`/`403` token/scope ·
`502` Transbank falló o no respondió (el pago queda `REJECTED`) · `503` faltan variables `WEBPAY_*`.

`buyOrder` = `"P360-" + paymentId + "-" + milisegundos en base 36`, recortado a 26 caracteres. Es único
porque el `paymentId` es único (aunque el recorte se coma los milisegundos, el id siempre cabe entero).

### GET|POST /payments/webpay/return (público)
| Llega | Significa | Resultado |
|---|---|---|
| `token_ws` | pago terminado | commit: `APPROVED` si `response_code = 0`, `status = AUTHORIZED` **y** el monto coincide; si no, `REJECTED` |
| `TBK_TOKEN` (+ `TBK_ORDEN_COMPRA`, `TBK_ID_SESION`) | el usuario anuló | `CANCELLED`, sin commit |
| `token_ws` + `TBK_TOKEN` | error en el formulario de Webpay | `CANCELLED`, sin commit |
| solo `TBK_ORDEN_COMPRA` + `TBK_ID_SESION` | se agotó el tiempo | `CANCELLED` |

- Se acepta `TBK_ID_SESION` y `TBK_ID_SESSION`. Si la sesión no corresponde al dueño del pago, no se toca.
- Si el pago **ya está en estado final** (por ejemplo, el usuario recargó la página), **no** se vuelve a
  confirmar (Transbank rechaza un segundo commit): se redirige con el estado que ya tiene.
- Si el commit falla por red, el pago queda `REJECTED` (sin commit, Transbank reversa la transacción sola).
- Siempre responde **302** al frontend; si no se encuentra el pago → `status=UNKNOWN`.
- Si falta `WEBPAY_FRONTEND_RESULT_URL` responde 503 **antes** de tocar el pago.

**¿Por qué esta ruta es pública y es seguro?** La llama el navegador al volver de Transbank y esa
redirección no lleva nuestro JWT. Solo sirve para confirmar un `token_ws` que Transbank emitió para
nuestro comercio: el commit se hace contra Transbank con nuestra llave secreta (un token inventado no
corresponde a ningún pago y no se confirma), y cada token se confirma una sola vez. El resto de las rutas
sigue exigiendo JWT y scope.

### Decisiones y limitaciones
- **Sin antifraude por tarjeta en Webpay:** la tarjeta nunca pasa por nosotros (no hay `cardHash`).
  El control de fraude de la tarjeta lo hace Transbank.
- **Logs:** nunca se loguea el `token_ws` ni la llave, solo el `paymentId`. `webpayToken` y `apiKey`
  están fuera del `toString()` (hay tests que lo verifican).
- **Retornos simultáneos:** no hay bloqueo si dos retornos con el mismo token llegan exactamente a la vez;
  en ese caso Transbank rechaza el segundo commit.
- **Monto distinto:** si Transbank autoriza un monto distinto al del pago, queda `REJECTED` y se loguea una
  advertencia (en un sistema real habría que anular la transacción en Transbank).

### Probar con las tarjetas de prueba (integración)
| Caso | Datos |
|---|---|
| Aprobada | VISA `4051 8856 0044 6623`, CVV `123`, cualquier fecha |
| Rechazada | MASTERCARD `5186 0595 5959 0568`, CVV `123` |
| Banco simulado | RUT `11.111.111-1`, clave `123` |

Código de comercio de integración: `597055555532`. La llave de pruebas la publica Transbank en
transbankdevelopers.cl ("Cómo empezar"); va solo en `WEBPAY_API_KEY`, nunca en el repo.
Anular: en la pantalla de Webpay, botón "Anular compra y volver" → `CANCELLED`.

## Variables de entorno
| Variable | Por defecto | Descripción |
|---|---|---|
| `AZURE_TENANT_ID` | tenant del proyecto | Tenant de Entra ID (emisor y JWKS) |
| `AZURE_CLIENT_ID` | client ID de la API | Audiencia válida del token |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers Kafka |
| `KAFKA_MAX_BLOCK_MS` | `3000` | Espera máxima al publicar |
| `KAFKA_LISTENER_AUTO_STARTUP` | `true` | Arrancar el consumidor de auditoría |
| `FRAUD_WINDOW_SECONDS` | `10` | Tamaño de la ventana |
| `FRAUD_MAX_ATTEMPTS` | `3` | Intentos permitidos en la ventana |
| `FRAUD_CLEANUP_INTERVAL_MS` | `60000` | Cada cuánto se limpia la memoria |
| `BLOCK_MINUTES` | `10` | Minutos de bloqueo del usuario tras el 429 |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / vacío | H2 |
| `SPRING_PROFILES_ACTIVE` | — | `prod` en el servidor |
| `WEBPAY_BASE_URL` | `https://webpay3gint.transbank.cl` | API de Transbank (integración) |
| `WEBPAY_COMMERCE_CODE` | vacío | Código de comercio (`597055555532` en integración) |
| `WEBPAY_API_KEY` | vacío | Llave secreta de Transbank. **Solo en `.env`** |
| `WEBPAY_RETURN_URL` | vacío | URL pública (Gateway) de `/payments/webpay/return` |
| `WEBPAY_FRONTEND_RESULT_URL` | vacío | Pantalla de resultado del frontend, ej. `https://front/#/pago/resultado` |
| `DYNAMODB_ENABLED` | `false` | `true` guarda la auditoría en DynamoDB y habilita `GET /payments/audit` |
| `DYNAMODB_PAYMENT_AUDIT_TABLE` | `pedidos360-payment-audit` | Nombre de la tabla (la crea el alumno) |
| `AWS_REGION` | `us-east-1` | Región de la tabla |

## Cómo probarlo
```bash
mvn clean package          # 132 tests, sin broker, Transbank ni AWS (DynamoDbClient simulado)
docker build -t ms-pago .
docker run -d --name ms-pago -p 8087:8087 --env-file .env ms-pago
```
Demo de fraude (fish o bash). Necesita un token de Azure con el scope `payments.write`; **sin token el
servicio responde 401** (valida el JWT por su cuenta). El usuario sale del `oid` del token, así que el body
no lleva `userId`:
```bash
set TOKEN "pega-aqui-el-access-token"   # en bash: TOKEN="pega-aqui-el-access-token"
for i in 1 2 3 4
  curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8087/payments \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"orderId":1,"cardNumber":"4111111111111111","cardHolder":"Demo","expiry":"12/30","cvv":"123","amount":1000}'
end
# 201 201 201 429   (en bash: for i in 1 2 3 4; do ...; done)
# Sin el header Authorization (o con un token vencido/inválido): 401 401 401 401
```
Luego `GET /payments/fraud-alerts` con un token Admin, y en Kafka UI revisa el tópico `payment-attempts`:
los 4 mensajes quedan en la **misma partición**.
