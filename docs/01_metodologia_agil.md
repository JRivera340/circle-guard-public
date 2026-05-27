# 1. Metodología Ágil y Branching

## Marco de Trabajo: Scrum

Trabajamos con **Scrum** usando sprints de 1 semana. El equipo asumió los roles de forma rotativa dado que éramos pocos, pero mantuvimos las ceremonias (planning, daily corta, review y retro al cierre de cada sprint).

Roles definidos:
- **Product Owner:** define qué requisitos técnicos tienen prioridad
- **Scrum Master:** detecta y elimina bloqueos, sobre todo en CI/CD y configuración del cluster
- **Dev Team:** implementa los microservicios, pipelines e infraestructura

## Estrategia de Branching: GitFlow

| Rama | Propósito | Deploy automático |
|------|-----------|-------------------|
| `main` | Producción estable | `circleguard-prod` (con approval gate) |
| `develop` | Integración continua | `circleguard-dev` |
| `staging` | Pre-producción | `circleguard-staging` |
| `feature/*` | Desarrollo de funcionalidades | — |
| `hotfix/*` | Correcciones urgentes en prod | `circleguard-prod` (fast-track) |

Las ramas `main` y `develop` están protegidas: requieren PR aprobado + CI verde antes de aceptar cualquier merge.

---

## Sprint 1 (Semana 1): Infraestructura y CI/CD Base

**Meta:** Tener el cluster K8s funcionando con el pipeline CI/CD completo y los 6 microservicios desplegados en dev.

**Capacidad:** 16 puntos de historia

### Historias de Usuario

| ID | Historia | Puntos | Estado |
|----|----------|--------|--------|
| US-01 | Como DevOps Engineer, quiero Terraform para provisionar clusters GKE, para no tener que hacer el setup del cluster a mano cada vez | 8 | ✅ |
| US-02 | Como developer, quiero un pipeline CI/CD automático con Docker Build & Push, para que los commits se desplieguen solos en dev | 5 | ✅ |
| US-03 | Como operador, quiero las imágenes Docker escaneadas con Trivy, para detectar vulnerabilidades HIGH/CRITICAL antes de que lleguen a producción | 3 | ✅ |

**Velocidad realizada:** 16 puntos

### Criterios de Aceptación Sprint 1
1. `terraform validate` pasa sin errores en los 3 entornos
2. Push a `develop` dispara el pipeline completo (build → test → docker → deploy-dev)
3. Reporte Trivy generado por cada imagen en cada build
4. Los 6 servicios están en estado Running en `circleguard-dev` después del merge

### Retrospectiva Sprint 1
- **Lo que funcionó:** Paralelizar los Docker builds en Jenkins redujo el tiempo de pipeline de ~12 minutos a ~4 minutos
- **Lo que costó:** Testcontainers necesita Docker disponible en el agente de Jenkins; tuvimos que configurar un nodo con Docker-in-Docker habilitado
- **Acción tomada:** Agregar `nodeSelector: docker-builder: "true"` en el pod agent de Jenkins para que los tests de integración siempre vayan a ese nodo

---

## Sprint 2 (Semana 2): Observabilidad, Seguridad y Patrones

**Meta:** Stack de monitoreo completo, patrones de resiliencia implementados y RBAC configurado.

**Capacidad:** 19 puntos de historia

### Historias de Usuario

| ID | Historia | Puntos | Estado |
|----|----------|--------|--------|
| US-04 | Como operador, quiero dashboards Grafana con HTTP RPS, error rate y JVM heap, para ver el estado de los servicios sin tener que consultar logs manualmente | 5 | ✅ |
| US-05 | Como operador, quiero logs centralizados en Kibana (índice `circleguard-*`), para investigar errores de producción sin necesitar acceso SSH a los nodos | 5 | ✅ |
| US-06 | Como developer, quiero trazas distribuidas en Jaeger, para saber exactamente dónde se pierde el tiempo en una request que pasa por varios servicios | 3 | ✅ |
| US-07 | Como developer, quiero Circuit Breaker en gateway-service, para que si auth-service se cae no se caiga todo el sistema | 3 | ✅ |
| US-08 | Como security engineer, quiero RBAC y NetworkPolicies en K8s, para que cada pod solo tenga acceso a los recursos que necesita | 3 | ✅ |

**Velocidad realizada:** 19 puntos

### Criterios de Aceptación Sprint 2
1. Dashboard Grafana muestra métricas en tiempo real de los 6 servicios
2. Logs de todos los pods visibles en Kibana bajo índice `circleguard-YYYY.MM.DD`
3. Jaeger UI muestra trazas entre gateway → auth → form
4. Circuit Breaker abre después de 5 fallos consecutivos; fallback retorna HTTP 503
5. `kubectl auth can-i get secrets --as=system:serviceaccount:circleguard-dev:default` retorna `no`

### Retrospectiva Sprint 2
- **Lo que funcionó:** Los overlays de Kustomize permiten promover configuración de dev → staging → prod sin duplicar manifests
- **Lo que costó:** El stack ELK consume alrededor de 3GB de RAM; en staging tuvimos que ampliar el node pool para que todo cupiera
- **Acción tomada:** Configurar ILM (Index Lifecycle Management) en Elasticsearch para rotar automáticamente los índices de más de 7 días

---

## Board de Proyecto

Usamos **GitHub Projects** con columnas: Backlog → In Progress → Review → Done.

Cada Pull Request hace referencia al ID de historia correspondiente, por ejemplo: `feat(US-04): add Grafana dashboard`.

## Herramientas

| Herramienta | Propósito |
|-------------|-----------|
| Git / GitHub | Control de versiones y code reviews |
| Jenkins | Automatización CI/CD |
| GitHub Projects | Gestión ágil del sprint |
| Conventional Commits | Formato estándar para semver automático |
