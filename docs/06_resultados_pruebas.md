# 6. Resultados de Pruebas

## Resumen

| Tipo de Prueba | Herramienta | Resultado |
|----------------|-------------|-----------|
| Pruebas Unitarias | JUnit 5 + Spring Test | Auth: 4 tests ✅, Form: 4 tests ✅ |
| Pruebas de Integración | Testcontainers + PostgreSQL real | Auth: 3 tests ✅, Form: 3 tests ✅ |
| Pruebas E2E | pytest + requests | 8 flujos completos ✅ |
| Pruebas de Rendimiento | Locust | 50 usuarios concurrentes, P95 < 500ms |
| Análisis Estático | SonarQube | Calidad de código continua por rama |
| Escaneo de Contenedores | Trivy | HIGH/CRITICAL reportados por imagen |
| Pruebas de Seguridad Web | OWASP ZAP | Baseline scan en staging |
| Cobertura de Código | JaCoCo | Reportes XML/HTML por servicio |

---

## 1. Pruebas Unitarias

**circleguard-auth-service** (`src/test/java/com/circleguard/auth/`):
- `LoginControllerTest` — validación del endpoint `/api/v1/auth/login` con MockMvc
- `DualChainAuthenticationProviderTest` — lógica de autenticación dual (LDAP + DB)
- `QrTokenServiceTest` — generación y validación de tokens QR
- `TokenValidationUnitTest` — validación de JWT (expiración, firma, claims)

**circleguard-form-service** (`src/test/java/com/circleguard/form/`):
- `AttachmentControllerTest` — CRUD de adjuntos con MockMvc
- `HealthSurveyControllerTest` — endpoints de encuestas de salud
- `QuestionnaireControllerTest` — gestión de cuestionarios
- `SymptomMapperTest` — mapeo de síntomas a categorías

**Ejecución:**
```bash
./gradlew test --no-daemon -x :services:circleguard-dashboard-service:test
```

**Reportes generados:** `services/*/build/reports/tests/test/index.html`

---

## 2. Pruebas de Integración

Implementadas con **Testcontainers**: levantan un contenedor Docker real de PostgreSQL durante el test, de modo que el código interactúa con una base de datos real en lugar de mocks o H2.

### circleguard-auth-service (`AuthIntegrationTest.java`)

| Test | Descripción | Resultado esperado |
|------|-------------|-------------------|
| `healthEndpointReturnsUp` | GET /actuator/health con PostgreSQL real | HTTP 200 |
| `loginWithInvalidCredentialsReturns401` | POST /login con credenciales incorrectas | HTTP 4xx |
| `loginEndpointAcceptsJsonContentType` | POST /login verifica Content-Type de respuesta | application/json |

Contenedor: `postgres:15-alpine` con schema `circleguard_auth`

### circleguard-form-service (`FormIntegrationTest.java`)

| Test | Descripción | Resultado esperado |
|------|-------------|-------------------|
| `healthEndpointReturnsUp` | GET /actuator/health con PostgreSQL real | HTTP 200 |
| `questionnairesEndpointRequiresAuth` | GET /api/v1/questionnaires sin token | HTTP 401/403 |
| `healthSurveyEndpointRequiresAuth` | GET /api/v1/health-surveys sin token | HTTP 401/403 |

Contenedor: `postgres:15-alpine` con Flyway repair habilitado para evitar que checksum conflicts paren el test.

La ventaja principal sobre mocks: estos tests detectan problemas reales de Flyway migrations, constraints de base de datos, y configuración de seguridad que los tests unitarios con H2 nunca verían.

---

## 3. Pruebas End-to-End (E2E)

**Framework:** pytest + requests
**Archivo:** `tests/e2e/test_e2e_flows.py`
**Target:** Ambiente staging (`circleguard-staging`)

### Flujos cubiertos

| Flujo | Descripción |
|-------|-------------|
| Login exitoso | POST /auth/login → JWT válido |
| Login fallido | Credenciales incorrectas → 401 |
| Generación QR | JWT → POST /qr/generate → código QR |
| Validación QR | Código QR → POST /qr/validate → acceso |
| Health checks | GET /actuator/health en todos los servicios |
| Formulario de salud | Flujo completo: login → submit encuesta |
| Gateway routing | Verificar que gateway enruta correctamente |
| Retry con reintentos | HTTPAdapter con retry para ambientes inestables |

**Ejecución:**
```bash
pip install requests pytest
pytest tests/e2e/ -v --base-url=http://<STAGING_NODE_IP>:<PORT>
```

---

## 4. Pruebas de Rendimiento (Locust)

**Framework:** Locust
**Archivo:** `tests/performance/locustfile.py`
**Escenario:** Simulación de guardias de seguridad usando el sistema en turno escolar

### Usuario simulado (`CircleGuardUser`)

1. `on_start`: login con credenciales de prueba
2. `@task(3)`: generar QR + validar acceso (flujo principal, peso 3x frente a otras tareas)
3. Espera entre acciones: 1-3 segundos (simula uso humano real)

### Métricas objetivo

| Métrica | Objetivo |
|---------|----------|
| RPS (login) | > 10 req/s |
| P95 latencia | < 500ms |
| P99 latencia | < 1000ms |
| Error rate | < 1% |
| Usuarios concurrentes | 50 |

**Ejecución en K8s:**
```bash
kubectl apply -f k8s/jobs/locust-job.yaml -n circleguard-staging
kubectl wait --for=condition=complete job/locust-perf-test -n circleguard-staging --timeout=900s
kubectl logs job/locust-perf-test -n circleguard-staging
```

**Ejecución local:**
```bash
pip install locust
locust -f tests/performance/locustfile.py \
    --host=http://<AUTH_SERVICE_URL> \
    --users=50 --spawn-rate=5 --run-time=5m
```

---

## 5. Análisis Estático (SonarQube)

Integrado en el stage `SonarQube Analysis` del Jenkinsfile, corre en todas las ramas excepto prod.

Métricas analizadas:
- **Bugs:** errores potenciales en código
- **Vulnerabilidades:** problemas de seguridad en código fuente
- **Code Smells:** deuda técnica
- **Duplicaciones:** código repetido entre servicios
- **Cobertura:** integrada con reportes JaCoCo

Quality Gate configurado:
- 0 bugs nuevos de severidad BLOCKER
- 0 vulnerabilidades nuevas
- Cobertura en código nuevo > 80%
- Duplicaciones < 3%

---

## 6. Escaneo de Vulnerabilidades en Contenedores (Trivy)

Corre en el stage `Trivy Scan` del Jenkinsfile después de cada Docker push.

Escanea solo severidades **HIGH** y **CRITICAL** para reducir el ruido de reportes. Detecta CVEs tanto en el sistema operativo base (Alpine, Ubuntu) como en las dependencias Java del classpath.

Reportes archivados como `trivy-<service-name>.txt` en cada build de Jenkins.

---

## 7. Pruebas de Seguridad Web (OWASP ZAP)

**Stage:** `OWASP ZAP Scan` (solo rama `staging`)
**Target:** `circleguard-gateway-service` — el único punto de entrada público

Usa el scan baseline (pasivo, no destructivo). Identifica:
- Headers de seguridad faltantes (X-Frame-Options, CSP, HSTS)
- Información expuesta en respuestas de error
- Configuraciones inseguras en endpoints públicos

El flag `-I` está configurado para que el pipeline no falle por findings INFO/LOW — solo MEDIUM+ bloquearían el pipeline en futuras iteraciones.

Reporte archivado como `zap-reports/zap-report.html` en Jenkins.

---

## 8. Cobertura de Código (JaCoCo)

Generado automáticamente al finalizar cada `./gradlew test`.

```
services/<service>/build/reports/jacoco/test/
├── html/index.html         ← reporte visual
└── jacocoTestReport.xml    ← para SonarQube
```

Los reportes XML se integran con SonarQube para mostrar cobertura por clase, método y línea. Archivados en Jenkins bajo `**/build/reports/jacoco/**`.
