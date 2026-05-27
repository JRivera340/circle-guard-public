# Release Notes

## v1.2.0 — 2026-05-26

### Features
- feat(terraform): módulos GKE modular (vpc-network, gke-cluster, node-pool) con 3 environments (dev/stage/prod) y GCS remote state
- feat(monitoring): Prometheus + Grafana con dashboards pre-provisionados para HTTP RPS, error rate, JVM heap y Circuit Breaker state
- feat(logging): ELK Stack (Elasticsearch 8.13 + Logstash + Kibana) con Filebeat DaemonSet para autodiscovery de pods K8s
- feat(tracing): Jaeger all-in-one + Micrometer tracing en todos los servicios (endpoint Zipkin v2)
- feat(patterns): Circuit Breaker Resilience4j en gateway-service con fallback y configuración por instancia
- feat(security): RBAC ServiceAccounts + Roles + RoleBindings para cada microservicio
- feat(security): NetworkPolicy default-deny + allow-intra-namespace por namespace
- feat(ci): SonarQube analysis stage con reporte XML para todos los módulos
- feat(ci): Trivy vulnerability scan (HIGH/CRITICAL) con archivado de reportes por imagen
- feat(ci): Manual approval gate antes de deploy a producción (timeout 30 min)
- feat(ci): Email notification automático en fallos de pipeline
- feat(ci): Semantic versioning automático (MAJOR.MINOR.PATCH) via git tags en rama main
- feat(ci): JaCoCo coverage reports XML+HTML archivados en cada build
- feat(ci): OWASP ZAP baseline scan en staging con reporte HTML
- feat(tests): Integration tests con Testcontainers para auth-service y form-service

### Bug Fixes
- fix(form-service): force disable Flyway validation + enable repair para ambientes con schema divergente
- fix(ci): rollback auto-prod ahora usa withCredentials para KUBECONFIG

### Infrastructure
- k8s/monitoring/: namespace + Prometheus + Grafana + Alertmanager
- k8s/logging/: namespace + Elasticsearch + Logstash + Kibana + Filebeat
- k8s/tracing/: namespace + Jaeger all-in-one
- k8s/base/rbac/: service-accounts + roles + rolebindings
- k8s/base/network-policies/: default-deny + allow-intra-namespace
- terraform/: structure modules + environments completa

### Documentation
- docs/01_metodologia_agil.md: sprints completos con historias de usuario y retrospectivas
- docs/04_patrones_diseno.md: patrones identificados + 3 nuevos implementados con diagramas
- docs/07_change_management.md: proceso formal + criterios rollback + SemVer + Conventional Commits

---

## v1.1.0 — 2026-05-19

### Features
- feat(ci): parallel Docker build & push para 6 servicios (reduce tiempo 12→4 min)
- feat(k8s): Kustomize overlays dev/staging/prod con image tag patching
- feat(ci): post-deploy synthetic transaction validation (login→QR→gate)
- feat(ci): auto-rollback en fallo de producción para 3 servicios críticos
- feat(ci): Locust performance tests via K8s Job en staging

### Bug Fixes
- fix: enable Flyway repair para form y promotion services
- fix: optimize resource limits y Recreate strategy para evitar pods Pending

---

## v1.0.0 — 2026-05-10

### Initial Release
- 7 microservicios Spring Boot 3.2 / Kotlin (auth, identity, form, promotion, gateway, notification, dashboard)
- Jenkins pipeline con stages: checkout → unit tests → build JARs → docker build/push → deploy → smoke tests
- K8s base manifests: PostgreSQL, Neo4j, Redis, Kafka, OpenLDAP, 6 microservicios
- E2E test suite con pytest + requests
- Locust performance tests (login → QR generation → gate validation)
- GitFlow branching strategy (main/develop/staging/feature/hotfix)
