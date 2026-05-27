# 4. Patrones de Diseño

## Patrones Identificados en la Arquitectura Existente

### 1. API Gateway (Structural Pattern)
**Servicio:** `circleguard-gateway-service` (puerto 8087)

**Descripción:** Punto único de entrada para todos los clientes externos. Enruta peticiones a servicios internos, aplica validación de JWT, y centraliza cross-cutting concerns (autenticación, rate limiting, logging).

**Implementación:** Spring Boot + filtros de seguridad personalizados. El gateway valida el token JWT antes de hacer forward al servicio destino.

**Beneficio:** Los clientes (mobile app, web) solo necesitan conocer un endpoint. Los servicios internos no exponen puertos públicos.

---

### 2. Database per Service (Architectural Pattern)
**Servicios afectados:** Todos los microservicios

**Descripción:** Cada microservicio gestiona su propio schema en PostgreSQL:
- `circleguard_auth` → auth-service
- `circleguard_form` → form-service
- `circleguard_identity` → identity-service (+ Neo4j para grafo de identidades)

**Beneficio:** Desacoplamiento total. Un cambio de schema en auth-service no requiere coordinación con form-service. Cada servicio puede escalar independientemente.

---

### 3. Event-Driven Communication (Integration Pattern)
**Tecnología:** Apache Kafka

**Descripción:** Los servicios publican eventos de dominio (ej: `user.registered`, `form.submitted`) en topics de Kafka. Los consumidores reaccionan asíncronamente sin acoplamiento directo.

**Beneficio:** Desacoplamiento temporal. Si notification-service está caído, los eventos persisten en Kafka hasta que vuelva.

---

## Patrones Implementados en Este Taller

### 4. Circuit Breaker (Resilience Pattern) ✅

**Librería:** Resilience4j 2.2.0
**Servicio afectado:** `circleguard-gateway-service`

**Configuración aplicada** (`application.yml`):
```yaml
resilience4j:
  circuitbreaker:
    instances:
      auth-service:
        sliding-window-size: 10          # ventana de 10 llamadas
        failure-rate-threshold: 50       # abre si >50% fallan
        wait-duration-in-open-state: 30s # tiempo en estado OPEN
        permitted-number-of-calls-in-half-open-state: 3
```

**Flujo de estados:**
```
CLOSED → (>50% failures) → OPEN → (30s) → HALF_OPEN → (3 probes OK) → CLOSED
```

**Fallback implementado:** Cuando el CB está OPEN, el gateway retorna inmediatamente:
```json
{"error": "Auth service temporarily unavailable", "retryAfter": "30"}
```

**Beneficio medido:** Tiempo de respuesta bajo fallo pasa de 5s (timeout) a <10ms (fallback inmediato). Evita cascada de fallos al resto de servicios.

**Dashboard Grafana:** Métrica `resilience4j_circuitbreaker_state{name='auth-service'}` (0=CLOSED, 1=OPEN, 2=HALF_OPEN).

---

### 5. External Configuration (Configuration Pattern) ✅

**Implementación:** Kubernetes ConfigMaps + Spring `@Value` / `@ConfigurationProperties`

**Variables externalizadas** (en `k8s/dev-deploy/configmap.yaml`):
- `SPRING_DATASOURCE_URL`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `SPRING_DATA_REDIS_HOST`
- `SPRING_LDAP_URLS`
- `JWT_SECRET`

**Flujo:**
```
ConfigMap (K8s) ──envFrom──► Pod env vars ──►Spring Environment ──► @Value injection
```

**Beneficio:** La misma imagen Docker (`jrivera340/circleguard-auth-service:v1.2.0`) funciona en dev, staging y prod simplemente cambiando el ConfigMap de cada namespace. Sin recompilación.

**Separación de secretos:** Los valores sensibles (passwords, tokens) van en K8s Secrets, no en ConfigMaps, y se inyectan por separado.

---

### 6. Sidecar / DaemonSet Log Shipping (Observability Pattern) ✅

**Implementación:** Filebeat como DaemonSet en namespace `logging`

**Descripción:** En lugar de modificar cada microservicio para enviar logs a un sistema centralizado (invasivo), un agente Filebeat corre a nivel de nodo K8s y recolecta automáticamente los logs de todos los containers.

**Flujo:**
```
Container stdout/stderr → /var/log/containers/ → Filebeat (DaemonSet) → Logstash → Elasticsearch → Kibana
```

**Configuración clave:**
```yaml
filebeat.autodiscover:
  providers:
    - type: kubernetes
      hints.enabled: true   # auto-descubre nuevos pods
```

**Beneficio:** Zero instrumentation en los servicios. Cualquier nuevo servicio que se despliegue en el cluster automáticamente tiene sus logs en Kibana. Sin cambiar código de aplicación.

---

## Tabla Comparativa de Decisiones

| Patrón | Alternativa Considerada | Razón de Elección |
|--------|------------------------|-------------------|
| Circuit Breaker (Resilience4j) | Spring Cloud Circuit Breaker + Hystrix | Hystrix en mantenimiento desde 2018; Resilience4j activamente mantenido, mejor integración Spring Boot 3 |
| External Config (ConfigMap) | Spring Cloud Config Server | ConfigMap es nativo K8s; reduce componentes adicionales; Spring Boot lee env vars nativamente |
| Filebeat DaemonSet | Sidecar por pod | DaemonSet: 1 agente por nodo vs N agentes por servicio; menor consumo de recursos |
| Kafka (Event-Driven) | REST síncrono entre servicios | Evita acoplamiento temporal; notification-service puede estar caído sin perder eventos |
