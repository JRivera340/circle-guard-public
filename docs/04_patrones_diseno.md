# 4. Patrones de Diseño

## Patrones en la Arquitectura Existente

Estos patrones ya estaban presentes en la base del proyecto antes de este taller. Los documentamos para tener un registro claro de las decisiones de diseño.

### 1. API Gateway
**Servicio:** `circleguard-gateway-service` (puerto 8087)

Punto único de entrada para todos los clientes externos. Enruta las peticiones a los servicios internos, valida el JWT, y centraliza preocupaciones transversales como autenticación, rate limiting y logging.

**Implementación:** Spring Boot con filtros de seguridad personalizados. El gateway valida el token antes de hacer forward al servicio destino. Los clientes (app móvil, web) solo necesitan conocer este endpoint; los servicios internos no exponen puertos públicos.

---

### 2. Database per Service
**Servicios afectados:** Todos

Cada microservicio gestiona su propio schema en PostgreSQL:
- `circleguard_auth` → auth-service
- `circleguard_form` → form-service
- `circleguard_identity` → identity-service (más Neo4j para grafo de identidades)

El beneficio principal es el desacoplamiento: un cambio de schema en auth-service no requiere coordinar con form-service. Cada servicio puede escalar de forma independiente.

---

### 3. Event-Driven Communication
**Tecnología:** Apache Kafka

Los servicios publican eventos de dominio (ej: `user.registered`, `form.submitted`) en topics de Kafka. Los consumidores reaccionan de forma asíncrona, sin acoplamiento directo entre servicios.

El caso más claro de beneficio: si notification-service está caído, los eventos persisten en Kafka hasta que vuelva. Con REST síncrono, esos eventos simplemente se perderían.

---

## Patrones Implementados en Este Taller

### 4. Circuit Breaker ✅

**Librería:** Resilience4j 2.2.0
**Servicio:** `circleguard-gateway-service`

Implementado para proteger al sistema cuando auth-service no responde. Sin Circuit Breaker, cada request a un servicio caído espera el timeout completo (varios segundos); con CB, después de suficientes fallos el circuito "abre" y las requests reciben respuesta inmediata de error.

**Configuración en `application.yml`:**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      auth-service:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
```

**Estados:**
```
CLOSED → (>50% fallos en ventana de 10 llamadas) → OPEN → (30s) → HALF_OPEN → (3 probes OK) → CLOSED
```

**Fallback:** Cuando el circuito está OPEN, el gateway retorna inmediatamente:
```json
{"error": "Auth service temporarily unavailable", "retryAfter": "30"}
```

El tiempo de respuesta bajo fallo pasa de ~5s (esperar el timeout) a <10ms (fallback directo). La métrica `resilience4j_circuitbreaker_state{name='auth-service'}` en Grafana muestra el estado en tiempo real (0=CLOSED, 1=OPEN, 2=HALF_OPEN).

---

### 5. External Configuration ✅

**Implementación:** Kubernetes ConfigMaps + Spring `@Value` / `@ConfigurationProperties`

Variables externalizadas en `k8s/dev-deploy/configmap.yaml`:
- `SPRING_DATASOURCE_URL`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `SPRING_DATA_REDIS_HOST`
- `SPRING_LDAP_URLS`
- `JWT_SECRET`

```
ConfigMap (K8s) ──envFrom──► env vars del Pod ──► Spring Environment ──► @Value
```

La misma imagen Docker (`jrivera340/circleguard-auth-service:v1.2.0`) funciona en dev, staging y prod cambiando únicamente el ConfigMap de cada namespace. Los valores sensibles (passwords, tokens) van en K8s Secrets, separados del ConfigMap.

---

### 6. Sidecar / DaemonSet para Log Shipping ✅

**Implementación:** Filebeat como DaemonSet en namespace `logging`

En lugar de modificar cada microservicio para enviar logs a un sistema centralizado (lo cual requeriría cambios en el código de aplicación), un agente Filebeat corre a nivel de nodo K8s y recoge los logs de todos los containers automáticamente.

```
Container stdout/stderr → /var/log/containers/ → Filebeat (DaemonSet) → Logstash → Elasticsearch → Kibana
```

Con `hints.enabled: true`, cualquier nuevo servicio que se despliegue en el cluster aparece automáticamente en Kibana, sin tocar código de aplicación ni agregar configuración por servicio.

---

## Resumen de Decisiones

| Patrón | Alternativa Considerada | Por qué esta opción |
|--------|------------------------|---------------------|
| Circuit Breaker (Resilience4j) | Spring Cloud Circuit Breaker + Hystrix | Hystrix lleva en modo mantenimiento desde 2018; Resilience4j tiene mejor integración con Spring Boot 3 |
| External Config (ConfigMap) | Spring Cloud Config Server | ConfigMap es nativo de K8s; evita un componente adicional; Spring Boot lee env vars directamente |
| Filebeat DaemonSet | Sidecar por pod | 1 agente por nodo es más eficiente que N agentes por servicio |
| Kafka (Event-Driven) | REST síncrono entre servicios | Desacoplamiento temporal; notification-service puede caerse sin perder eventos |
