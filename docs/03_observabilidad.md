# 3. Stack de Observabilidad

## Visión General

Implementamos tres pilares de observabilidad: métricas (Prometheus + Grafana), logs centralizados (ELK) y trazas distribuidas (Jaeger). Los tres stacks corren en el cluster K8s y se acceden vía NodePort.

---

## Métricas: Prometheus + Grafana

### Cómo funciona

Cada microservicio expone el endpoint `/actuator/prometheus` gracias a la dependencia `micrometer-registry-prometheus`. Prometheus scrapea ese endpoint cada 15 segundos y almacena las métricas en su base de datos de series de tiempo. Grafana consulta Prometheus y visualiza todo en dashboards.

### Configuración de Prometheus

El ConfigMap `k8s/monitoring/prometheus/prometheus-configmap.yaml` define los targets de scraping:

```yaml
scrape_configs:
  - job_name: 'circleguard-auth'
    static_configs:
      - targets: ['circleguard-auth-service.circleguard-dev:8180']
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
```

Se repite para cada uno de los 6 servicios con sus respectivos puertos.

Los pods también tienen anotaciones para que Prometheus los descubra automáticamente:
```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8180"
  prometheus.io/path: "/actuator/prometheus"
```

### Dashboard Grafana

El dashboard `CircleGuard Services` tiene 6 paneles:

| Panel | Métrica | Tipo |
|-------|---------|------|
| HTTP RPS por servicio | `http_server_requests_seconds_count` | Graph |
| Error rate (5xx) | `rate(http_server_requests_seconds_count{status=~"5.."}[5m])` | Graph |
| JVM Heap usado | `jvm_memory_used_bytes{area="heap"}` | Gauge |
| Auth logins/segundo | `rate(http_server_requests_seconds_count{uri="/api/v1/auth/login"}[1m])` | Stat |
| Circuit Breaker state | `resilience4j_circuitbreaker_state` | State timeline |
| Latencia P99 | `histogram_quantile(0.99, ...)` | Graph |

**Acceso:** `http://<NODE_IP>:30030` — usuario `admin`, contraseña `circleguard2025`

### Alertas (Alertmanager)

Configurado para enviar emails cuando:

| Alerta | Condición |
|--------|-----------|
| Alta tasa de errores | HTTP 5xx > 5% durante 5 minutos |
| Latencia elevada | P99 > 2 segundos |
| Pod caído | Pod no está Running por más de 2 minutos |
| Circuit Breaker abierto | Estado `OPEN` en algún servicio |

---

## Logs: ELK Stack (Elasticsearch + Logstash + Kibana)

### Arquitectura del stack

```
Pods (stdout/stderr)
    │
    ▼
Filebeat DaemonSet (nodo K8s)
    │  beats:5044
    ▼
Logstash (pipeline: parse JSON → enrich)
    │
    ▼
Elasticsearch (índice circleguard-YYYY.MM.dd)
    │
    ▼
Kibana UI  →  http://<NODE_IP>:30601
```

### Filebeat

Corre como DaemonSet, un pod por nodo del cluster. Monta:
- `/var/log/containers/` — logs de todos los containers
- `/var/lib/docker/containers/` — metadata de Docker

Usa autodiscovery de Kubernetes:
```yaml
filebeat.autodiscover:
  providers:
    - type: kubernetes
      hints.enabled: true
```

Esto significa que cualquier nuevo pod que se despliegue en el cluster aparece automáticamente en Kibana sin configuración adicional.

### Logstash

Pipeline en 3 pasos:
1. **Input:** recibe de Filebeat en puerto 5044
2. **Filter:** parsea JSON si el log tiene ese formato; enriquece con metadatos del pod
3. **Output:** envía a Elasticsearch con índice `circleguard-%{+YYYY.MM.dd}`

### Elasticsearch

Single-node para entornos no-productivos. Versión 8.13.0 con `xpack.security.enabled: false` para simplificar la configuración interna del cluster. El initContainer setea `vm.max_map_count=262144` que ES requiere para funcionar.

### Kibana

**Acceso:** `http://<NODE_IP>:30601`
**Índice:** `circleguard-*`

Queries útiles:
```
# Ver todos los errores en dev
level: ERROR AND kubernetes.namespace: circleguard-dev

# Ver logs de un servicio específico
kubernetes.labels.app: circleguard-auth-service

# Errores de Flyway
message: "FlywayException" OR message: "Migration"
```

---

## Trazas Distribuidas: Jaeger

### Para qué sirve

Con trazas distribuidas podemos ver el recorrido completo de una request a través de los microservicios. Por ejemplo, una llamada a `/api/v1/auth/login` puede pasar por gateway → auth-service → PostgreSQL, y Jaeger muestra cuánto tiempo tomó cada salto.

### Configuración

Usamos Jaeger all-in-one (incluye collector, query y UI en un solo pod). Escucha en el endpoint compatible con Zipkin para recibir spans de los servicios.

Los microservicios envían trazas usando Micrometer Tracing con el bridge de Brave:
```yaml
# application.yml de cada servicio
management:
  tracing:
    sampling:
      probability: 1.0    # 100% en dev, reducir a 0.1 en prod
spring:
  zipkin:
    base-url: http://jaeger.tracing:9411
```

**Dependencias necesarias en cada servicio:**
```kotlin
implementation("io.micrometer:micrometer-tracing-bridge-brave")
implementation("io.zipkin.reporter2:zipkin-reporter-brave")
```

### Acceso

`http://<NODE_IP>:30686`

Para ver trazas: seleccionar servicio `circleguard-gateway-service` → buscar operaciones recientes → expandir spans para ver tiempos.

---

## Despliegue del Stack Completo

```bash
# Monitoreo
kubectl apply -f k8s/monitoring/namespace.yaml
kubectl apply -f k8s/monitoring/prometheus/
kubectl apply -f k8s/monitoring/grafana/
kubectl apply -f k8s/monitoring/alertmanager/

# Logging
kubectl apply -f k8s/logging/namespace.yaml
kubectl apply -f k8s/logging/elasticsearch/
kubectl apply -f k8s/logging/logstash/
kubectl apply -f k8s/logging/kibana/
kubectl apply -f k8s/logging/filebeat/

# Trazas
kubectl apply -f k8s/tracing/jaeger-all-in-one.yaml
```

Obtener la IP del nodo para acceder a las UIs:
```bash
kubectl get nodes -o wide | awk '{print $7}' | tail -n +2 | head -1
```

---

## Notas de Implementación

- En producción, Elasticsearch debería tener al menos 3 nodos con replicación. Para este taller usamos single-node.
- El sampling de Jaeger está al 100% en dev. En staging/prod conviene bajarlo a 10-20% para no saturar el collector.
- Los índices de Elasticsearch crecen rápido. Se recomienda configurar ILM (Index Lifecycle Management) para rotar índices con más de 7 días.
- Si Grafana no muestra datos, verificar que los pods tengan los labels `app.kubernetes.io/part-of: circleguard` para que Prometheus los detecte por autodiscovery.
