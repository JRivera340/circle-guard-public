# 7. Change Management y Release Process

## Proceso Formal de Gestión de Cambios

### Clasificación de Cambios

| Tipo | Ejemplos | Aprobaciones requeridas | Ventana de deploy |
|------|----------|------------------------|-------------------|
| **Standard** | Bug fixes, actualizaciones de dependencias menores | CI verde + 1 reviewer | Cualquier momento |
| **Normal** | Nuevas features, cambios de schema de BD | CI verde + 2 reviewers | Martes/Jueves 14-16h UTC-5 |
| **Emergency** | Hotfix producción crítico (P0/P1) | 1 aprobador on-call | Inmediato, con post-mortem obligatorio |

### Flujo Completo de un Cambio Normal

```
1. Developer crea rama feature/* desde develop
2. Implementa cambio + tests
3. Abre PR hacia develop
4. CI ejecuta: lint → unit tests → SonarQube → build → Trivy scan
5. Code review por 1 peer (mínimo)
6. Merge a develop → auto-deploy a circleguard-dev
7. Smoke tests automáticos en dev
8. QA verifica en dev
9. PR de develop → staging
10. CI ejecuta: integration tests → E2E → Locust → OWASP ZAP
11. Code review por 2 reviewers
12. Merge a staging → auto-deploy a circleguard-staging
13. PR de staging → main
14. Manual Approval Gate en Jenkins (timeout: 30 min, submitter: admin)
15. Deploy a circleguard-prod
16. Post-deploy synthetic transaction validation
17. Monitoreo en Grafana por 30 min post-deploy
```

### Criterios de Rollback

**Rollback automático** se dispara si:
- `kubectl rollout status` falla con timeout (configurado en Jenkinsfile)
- Post-deploy synthetic transaction retorna error

**Rollback manual** cuando (alertas Grafana):
- Error rate HTTP 5xx > 5% durante 5 minutos consecutivos
- Latencia P99 > 2 segundos
- CPU usage > 90% en todos los pods de un servicio

**Comando de rollback:**
```bash
# Rollback de un servicio específico
kubectl rollout undo deployment/<service-name> -n circleguard-prod

# Verificar que el rollback terminó
kubectl rollout status deployment/<service-name> -n circleguard-prod

# Ver historial de versiones
kubectl rollout history deployment/<service-name> -n circleguard-prod
```

---

## Semantic Versioning (SemVer)

Formato: `vMAJOR.MINOR.PATCH`

| Tipo de cambio | Incremento | Ejemplo |
|----------------|-----------|---------|
| Cambio incompatible de API | MAJOR | v1.0.0 → v2.0.0 |
| Nueva feature backwards-compatible | MINOR | v1.0.0 → v1.1.0 |
| Bug fix | PATCH | v1.0.0 → v1.0.1 |

**Generación automática:** Stage `Git Tag` en Jenkinsfile (rama `main`) calcula automáticamente el siguiente PATCH a partir del último tag en el repositorio.

### Conventional Commits

Todos los commits siguen el formato:
```
<type>(<scope>): <description>

feat(auth): add refresh token endpoint
fix(gateway): circuit breaker not opening on timeout errors
docs(terraform): add cost estimation for prod environment
ci(jenkins): add Trivy scan stage
```

Tipos: `feat`, `fix`, `docs`, `ci`, `refactor`, `test`, `chore`

---

## Sistema de Etiquetado de Releases

Cada release en `main` genera:
1. **Git tag** con formato `vMAJOR.MINOR.PATCH` (automático en Jenkins Stage 13)
2. **Release Notes** en archivo `RELEASE_NOTES_vX.Y.Z.md` (Jenkins Stage 12 via script)
3. **Docker images** etiquetadas con el mismo tag semver en Docker Hub

---

## Plan de Rollback por Servicio

| Servicio | Dependencias críticas | Tiempo estimado de rollback |
|----------|----------------------|----------------------------|
| auth-service | PostgreSQL, OpenLDAP | 2-3 min |
| gateway-service | Redis, auth-service | 1-2 min |
| form-service | PostgreSQL, Kafka | 2-3 min |
| promotion-service | PostgreSQL, Neo4j, Redis, Kafka | 3-5 min |
| notification-service | Kafka, SMTP, Twilio | 1-2 min |
| identity-service | PostgreSQL, Kafka | 2-3 min |

**RTO (Recovery Time Objective):** < 10 minutos para cualquier servicio
**RPO (Recovery Point Objective):** 0 (rollback a imagen anterior, sin pérdida de datos)
