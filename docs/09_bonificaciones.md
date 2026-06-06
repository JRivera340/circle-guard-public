# 9. Bonificaciones

Implementaciones adicionales sobre los requisitos base. Cada una es autocontenida y se aplica por separado del despliegue principal.

## Service Mesh — Istio (`k8s/service-mesh/istio.yaml`)

| Capacidad | Implementación |
|-----------|----------------|
| mTLS entre servicios | `PeerAuthentication` en modo `STRICT` para todo el namespace `circleguard-dev` |
| Traffic shifting / canary | `VirtualService` reparte 90% a `v1` y 10% a `v2` del gateway |
| Circuit breaker de mesh | `DestinationRule` con `outlierDetection` (expulsa instancias con 5xx) |
| Retries y connection pool | `VirtualService` con 3 reintentos; límites de conexiones en `DestinationRule` |
| Visualización | Kiali + Jaeger (el tracing ya está desplegado) |

Instalación: `istioctl install --set profile=demo -y`, etiquetar el namespace con `istio-injection=enabled` y reiniciar los deployments para inyectar el sidecar. El canary requiere etiquetar los pods con `version: v1` / `version: v2`.

## Chaos Engineering — Chaos Mesh (`k8s/chaos/experiments.yaml`)

| Experimento | Hipótesis | Resultado esperado |
|-------------|-----------|--------------------|
| `auth-pod-kill` (pod-kill cada 5 min) | Con 2+ réplicas y readiness probes, matar un pod no degrada el login | Sin errores 5xx visibles; el Service enruta a la réplica sana |
| `redis-latency` (delay 2s a Redis) | El Circuit Breaker del gateway abre y devuelve fallback | Respuesta `RED / temporarily unavailable` en <10ms en vez de colgarse 2s |
| `promotion-cpu-stress` (CPU 80%, 3 min) | Limits/HPA mantienen P99 aceptable o escalan | P99 sube de forma acotada; sin OOM ni caída |

Instalación vía Helm (`chaos-mesh/chaos-mesh`). Los resultados se observan en Grafana (panel P99 y Circuit Breaker State) y en Kibana (logs de error).

## FinOps — Monitoreo de costos (dashboard Grafana)

El dashboard `CircleGuard Services` incluye paneles de utilización que alimentan las decisiones de costo:
- **CPU solicitada vs asignable** del cluster (sobre `kube-state-metrics`) → detecta sobre/infra-aprovisionamiento.
- **Densidad de pods por nodo** → mide eficiencia del bin-packing.

Estrategias de ahorro documentadas en `docs/08_costos_infraestructura.md`: spot/preemptible en dev/stage, scale-to-zero fuera de horario, autoescalado del node pool y retención acotada de métricas/logs. Estimado: bajar de ~$800 a ~$450–500/mes en los tres ambientes.

> Requiere `kube-state-metrics` desplegado para las métricas `kube_*`.

## Multi-Cloud — AWS EKS además de GCP GKE

- Módulo `terraform/modules/eks-cluster` (espeja el módulo GKE): VPC, IAM, cluster EKS y node group autoescalable.
- Entorno `terraform/environments/aws-dev` con estado remoto en **S3** (GCP usa GCS).
- **Estrategia:** GKE como primario y EKS como secundario activo-pasivo. Las imágenes Docker en Docker Hub son agnósticas de cloud, así que el mismo `k8s/overlays/*` despliega en ambos.
- **Respaldo entre clouds:** Velero exporta backups del namespace a un bucket; el estado en grafo/SQL se replica por snapshots.
- **Balanceo entre proveedores:** DNS con health-checks (Route53 / Cloud DNS) repartiendo o conmutando tráfico ante caída de un cloud.
- **Comparativa de rendimiento:** misma prueba Locust contra cada cloud; se comparan P95/throughput (e2-standard-2 vs t3.large).
