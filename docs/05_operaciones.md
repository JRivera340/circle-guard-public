# 5. Guía de Operaciones y Mantenimiento

## Acceso a Herramientas

Obtener la IP del nodo primero:
```bash
kubectl get nodes -o wide | awk '{print $7}' | tail -n +2 | head -1
```

### Grafana (Monitoreo)
```
URL:      http://<NODE_IP>:30030
Usuario:  admin
Password: circleguard2025
```
Dashboard principal: `CircleGuard → CircleGuard Services`

### Kibana (Logs)
```
URL: http://<NODE_IP>:30601
```
Índice de logs: `circleguard-*`
Query para ver errores: `level: ERROR AND kubernetes.namespace: circleguard-dev`

### Jaeger (Trazas)
```
URL: http://<NODE_IP>:30686
```
Seleccionar servicio `circleguard-gateway-service` para ver trazas entre servicios.

### Prometheus
```
URL: http://<NODE_IP>:30090
```

### Jenkins
```
URL: http://<NODE_IP>:30080
```

---

## Operaciones del Día a Día

### Ver estado de todos los servicios
```bash
kubectl get pods -n circleguard-dev
kubectl get pods -n circleguard-staging
kubectl get pods -n circleguard-prod
```

### Ver logs en tiempo real
```bash
kubectl logs -f deployment/circleguard-auth-service -n circleguard-dev
kubectl logs -f deployment/circleguard-gateway-service -n circleguard-prod
```

### Reiniciar un servicio (rolling restart)
```bash
kubectl rollout restart deployment/circleguard-auth-service -n circleguard-prod
kubectl rollout status deployment/circleguard-auth-service -n circleguard-prod
```

### Escalar manualmente
```bash
kubectl scale deployment/circleguard-gateway-service --replicas=3 -n circleguard-prod
kubectl get deployment/circleguard-gateway-service -n circleguard-prod
```

### Ver uso de recursos
```bash
kubectl top pods -n circleguard-prod
kubectl top nodes
```

---

## Deploy Manual

### Deploy a DEV
```bash
cd k8s/overlays/dev
kustomize edit set image jrivera340/circleguard-auth-service:<TAG>
kubectl apply -k .
```

### Deploy a STAGING
```bash
kubectl apply -k k8s/overlays/staging
```

### Deploy a PROD
El deploy a producción **solo se hace vía Jenkins pipeline** en la rama `main`. No hacer deploys manuales a prod.

---

## Rollback

### Rollback rápido (versión anterior)
```bash
kubectl rollout undo deployment/circleguard-auth-service -n circleguard-prod
kubectl rollout undo deployment/circleguard-gateway-service -n circleguard-prod
```

### Ver historial de versiones
```bash
kubectl rollout history deployment/circleguard-auth-service -n circleguard-prod
```

### Rollback a versión específica
```bash
kubectl rollout undo deployment/circleguard-auth-service --to-revision=2 -n circleguard-prod
```

### Verificar que completó
```bash
kubectl rollout status deployment/circleguard-auth-service -n circleguard-prod --timeout=120s
```

---

## Troubleshooting

### Pod en CrashLoopBackOff
```bash
# Ver logs del crash anterior
kubectl logs deployment/circleguard-form-service -n circleguard-dev --previous

# Ver eventos del pod
kubectl describe pod -l app=circleguard-form-service -n circleguard-dev
```

### Pod en Pending
```bash
kubectl describe pod -l app=circleguard-promotion-service -n circleguard-dev
# Causas comunes: recursos insuficientes en nodos, nodeSelector que no coincide
```

### Servicio no responde
```bash
# Verificar que hay endpoints
kubectl get endpoints circleguard-auth-service -n circleguard-dev

# Test directo desde dentro del cluster
kubectl exec -it deployment/circleguard-gateway-service -n circleguard-dev -- \
    wget -qO- http://circleguard-auth-service:8180/actuator/health
```

### Flyway falla al iniciar
```bash
# Repair manual del schema
kubectl exec -it deployment/circleguard-form-service -n circleguard-dev -- \
    java -jar app.jar --spring.flyway.repair=true
# O setear en ConfigMap: SPRING_FLYWAY_REPAIR=true
```

### Circuit Breaker abierto (gateway retorna 503)
```bash
# Ver estado en Prometheus
curl http://<NODE_IP>:30090/api/v1/query?query=resilience4j_circuitbreaker_state

# El CB se resetea solo después de 30s
# Si persiste: revisar auth-service
kubectl logs deployment/circleguard-auth-service -n circleguard-dev | tail -50
```

---

## Mantenimiento

### Actualizar imagen sin pipeline
```bash
kubectl set image deployment/circleguard-auth-service \
    circleguard-auth-service=jrivera340/circleguard-auth-service:v1.2.1 \
    -n circleguard-prod
```

### Actualizar ConfigMap
```bash
kubectl edit configmap circleguard-config -n circleguard-prod
# Reiniciar pods para que tomen los nuevos valores
kubectl rollout restart deployment -l app.kubernetes.io/part-of=circleguard -n circleguard-prod
```

### Aplicar stack de monitoreo
```bash
kubectl apply -f k8s/monitoring/namespace.yaml
kubectl apply -f k8s/monitoring/prometheus/
kubectl apply -f k8s/monitoring/grafana/
kubectl apply -f k8s/monitoring/alertmanager/
```

### Aplicar stack de logging
```bash
kubectl apply -f k8s/logging/namespace.yaml
kubectl apply -f k8s/logging/elasticsearch/
kubectl apply -f k8s/logging/logstash/
kubectl apply -f k8s/logging/kibana/
kubectl apply -f k8s/logging/filebeat/
```

### Aplicar Jaeger
```bash
kubectl apply -f k8s/tracing/jaeger-all-in-one.yaml
```

---

## Alertas Configuradas

| Alerta | Condición | Acción sugerida |
|--------|-----------|-----------------|
| Error rate alto | HTTP 5xx > 5% por 5 min | Revisar logs, evaluar rollback |
| Latencia alta | P99 > 2s | Revisar recursos, escalar |
| Pod caído | Pod no Running por 2 min | Revisar CrashLoopBackOff |
| Circuit Breaker abierto | CB state = OPEN | Revisar auth-service |

Las alertas se envían por email via Alertmanager.

---

## TLS y Exposición Pública

El gateway es el único servicio expuesto a internet y va detrás de un Ingress con TLS terminado por `cert-manager`.

### Dependencias (una sola vez por cluster)
```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
```

### Aplicar TLS + Ingress
```bash
kubectl apply -f k8s/security/tls.yaml
kubectl get certificate -n circleguard-dev   # READY=True cuando el cert está emitido
```

En dev se usa un `ClusterIssuer` self-signed; para producción se cambia el `issuerRef` a un emisor ACME de Let's Encrypt sin tocar el Ingress. Para probar localmente, agregar `127.0.0.1 circleguard.local` al archivo hosts y acceder por `https://circleguard.local`.

## Gestión de Secretos y RBAC

- Los valores sensibles (passwords, JWT/QR secret) viven en K8s Secrets, separados de los ConfigMaps de configuración.
- Cada microservicio corre con su propia `ServiceAccount` y un `Role` de mínimo privilegio (solo lectura de configmaps/secrets de su namespace) — ver `k8s/base/rbac/`.
- Tráfico restringido por `NetworkPolicy` `default-deny` + `allow-intra-namespace` (ver `k8s/base/network-policies/`).
