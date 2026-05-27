# 1. Metodología Ágil y Branching

## Marco de Trabajo: Scrum

Adoptamos **Scrum** con sprints de 1 semana. Roles definidos:
- **Product Owner:** define prioridades de requisitos técnicos
- **Scrum Master:** elimina bloqueos en CI/CD y configuración de cluster
- **Dev Team:** implementa microservicios, pipelines e infraestructura

## Estrategia de Branching: GitFlow

| Rama | Propósito | Deploy automático |
|------|-----------|-------------------|
| `main` | Producción estable | `circleguard-prod` (con approval gate) |
| `develop` | Integración continua | `circleguard-dev` |
| `staging` | Pre-producción | `circleguard-staging` |
| `feature/*` | Desarrollo de funcionalidades | — |
| `hotfix/*` | Correcciones urgentes en prod | `circleguard-prod` (fast-track) |

**Protecciones activas:** `main` y `develop` requieren PR aprobado + CI verde antes de merge.

---

## Sprint 1 (Semana 1): Infraestructura y CI/CD Base

**Meta del Sprint:** Cluster K8s funcional + pipeline CI/CD completo con 6 microservicios desplegados.

**Capacidad:** 16 puntos de historia

### Historias de Usuario

| ID | Historia | Puntos | Estado |
|----|----------|--------|--------|
| US-01 | Como DevOps Engineer, quiero Terraform para provisionar clusters GKE, para no hacer setup manual del cluster | 8 | ✅ |
| US-02 | Como developer, quiero pipeline CI/CD automático con Docker Build & Push, para que mis commits se desplieguen solos en dev | 5 | ✅ |
| US-03 | Como operador, quiero imágenes Docker escaneadas con Trivy, para detectar vulnerabilidades HIGH/CRITICAL antes de producción | 3 | ✅ |

**Velocidad realizada:** 16 puntos

### Criterios de Aceptación Sprint 1
1. `terraform validate` pasa sin errores en los 3 entornos
2. Push a `develop` dispara pipeline completo (build → test → docker → deploy-dev)
3. Reporte Trivy generado por cada imagen en cada build
4. 6 servicios running en `circleguard-dev` tras merge

### Retrospectiva Sprint 1
- **Lo que funcionó:** Pipeline Jenkins paralelo para Docker build redujo tiempo de 12 min a 4 min
- **Lo que mejorar:** Testcontainers requiere Docker-in-Docker; necesita nodo con `docker-builder: true`
- **Acción tomada:** Configurar `nodeSelector: docker-builder: "true"` en el pod agent de Jenkins

---

## Sprint 2 (Semana 2): Observabilidad, Seguridad y Patrones

**Meta del Sprint:** Stack de monitoreo completo + patrones de resiliencia + RBAC.

**Capacidad:** 19 puntos de historia

### Historias de Usuario

| ID | Historia | Puntos | Estado |
|----|----------|--------|--------|
| US-04 | Como operador, quiero dashboards Grafana con HTTP RPS, error rate y JVM heap, para ver el estado de los servicios en tiempo real | 5 | ✅ |
| US-05 | Como operador, quiero logs centralizados en Kibana (índice `circleguard-*`), para investigar errores de producción sin acceso SSH | 5 | ✅ |
| US-06 | Como developer, quiero trazas distribuidas en Jaeger, para identificar cuellos de botella entre microservicios | 3 | ✅ |
| US-07 | Como developer, quiero Circuit Breaker en gateway-service, para que fallos en auth-service no cascateen a todos los usuarios | 3 | ✅ |
| US-08 | Como security engineer, quiero RBAC y NetworkPolicies en K8s, para que cada pod solo acceda a lo estrictamente necesario | 3 | ✅ |

**Velocidad realizada:** 19 puntos

### Criterios de Aceptación Sprint 2
1. Dashboard Grafana muestra métricas en tiempo real de los 6 servicios
2. Logs de todos los pods visibles en Kibana bajo índice `circleguard-YYYY.MM.DD`
3. Jaeger UI muestra traces entre gateway → auth → form
4. Circuit Breaker abre después de 5 fallos consecutivos; fallback retorna HTTP 503
5. `kubectl auth can-i get secrets --as=system:serviceaccount:circleguard-dev:default` retorna `no`

### Retrospectiva Sprint 2
- **Lo que funcionó:** Kustomize overlays permiten promotion dev→stage→prod sin duplicar manifests
- **Lo que mejorar:** ELK consume ~3GB RAM; necesario scaling del node pool en staging
- **Acción tomada:** Configurar ILM en Elasticsearch para rotar índices con más de 7 días

---

## Board de Proyecto

Utilizamos **GitHub Projects** (o equivalente) con columnas: Backlog → In Progress → Review → Done.

Cada Pull Request referencia el ID de historia (ej: `feat(US-04): add Grafana dashboard`).

## Herramientas Utilizadas

| Herramienta | Propósito |
|-------------|-----------|
| Git / GitHub | Control de versiones y PR reviews |
| Jenkins | CI/CD automation |
| GitHub Projects | Gestión ágil de sprints |
| Conventional Commits | Formato estándar de commits para semver automático |
