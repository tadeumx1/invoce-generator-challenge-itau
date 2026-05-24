# Postman collection

`invoice-generator.postman_collection.json` exercises every documented HTTP path of the
service: the two shipped sample payloads, the legacy Portuguese alias, and the three
HTTP 400 rejections. Schema is Postman v2.1.0.

## Import

In Postman: **File → Import → Upload Files →
`docs/postman/invoice-generator.postman_collection.json`**. Or import from a URL once the
repo is on GitHub. No separate environment file is needed — the collection ships a single
collection variable `baseUrl` defaulting to `http://localhost:8080`.

For Newman (CLI):

```bash
npx newman run docs/postman/invoice-generator.postman_collection.json
# Or against a different host:
npx newman run docs/postman/invoice-generator.postman_collection.json \
  --env-var baseUrl=https://invoice.example.com
```

## What's in it

| Folder | Request | Asserts |
| --- | --- | --- |
| Happy paths | `POST /api/orders/generate-invoice` (FISICA) | 200; `valor_frete = 10.48` (SUDESTE × 1.048); `valor_tributo_item = 0` (FISICA bracket-0); `X-Correlation-Id` echoed back. |
| Happy paths | `POST /api/orders/generate-invoice` (JURIDICA + SIMPLES_NACIONAL) | 200; `valor_tributo_item ≈ 138.7` (5840 > 5000 → rate 0.19, 730 × 0.19); `valor_frete = 75.46`; no English keys leak. |
| Happy paths | `POST /api/pedido/gerarNotaFiscal` (legacy alias) | 200; same contract as the modern path. |
| Rejections | JURIDICA + OUTROS | 400; `codigo = UNSUPPORTED_TAX_REGIME`. |
| Rejections | JURIDICA without `regime_tributacao` | 400; `codigo = INVALID_TAX_REGIME`. |
| Rejections | Delivery address with `regiao = null` | 400; `codigo = INVALID_DELIVERY_REGION`. |

Every request injects `X-Correlation-Id: postman-<scenario>` so the F-OBSERVABILITY MDC /
trace correlation is exercised. Read the value back from the response header to find the
request in `docker compose logs`, in Prometheus `invoice_*_total` counters, or in the
Jaeger UI at `http://localhost:16686`.

## Running the service locally before sending requests

```bash
./mvnw spring-boot:run                 # app only (Kafka publish ignored when broker absent)
# or full stack with Kafka + Jaeger:
docker compose up --build
```

Then run the whole collection in Postman with **Collection Runner**, or call individual
requests. After at least one successful run, scrape the SLI source meters from
`http://localhost:8080/actuator/prometheus` (see
[`docs/observability.md`](../observability.md)).

## Maintenance

Keep the bodies of the two "Happy paths" requests in sync with the JSON files under
`src/main/resources/paylods/` — they're the same fixtures. The rejection-path bodies are
trimmed-down variants of the same payloads with one field changed to trigger each domain
guard. When new HTTP routes or new rejection codes appear, extend this collection in the
same commit so reviewers always have a one-click exercise of the API.
