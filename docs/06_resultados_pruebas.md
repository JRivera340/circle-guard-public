# 6. Análisis de Resultados de Pruebas

## Resumen Ejecutivo

| Tipo de Prueba | Herramienta | Cobertura / Resultado |
|----------------|-------------|----------------------|
| Pruebas Unitarias | JUnit 5 + Spring Test | Auth: 4 tests ✅, Form: 4 tests ✅ |
| Pruebas de Integración | Testcontainers + PostgreSQL real | Auth: 3 tests ✅, Form: 3 tests ✅ |
| Pruebas E2E | pytest + requests | 8 flujos completos ✅ |
| Pruebas de Rendimiento | Locust | 50 usuarios concurrentes, <500ms P95 |
| Análisis Estático | SonarQube | Calidad de código continua por rama |
| Escaneo Seguridad Contenedores | Trivy | HIGH/CRITICAL reportados por imagen |
| Pruebas de Seguridad Web | OWASP ZAP | Baseline scan en staging |
| Cobertura de Código | JaCoCo | Reportes XML/HTML por servicio |

---

## 1. Pruebas Unitarias

### Servicios con cobertura unitaria

**circleguard-auth-service** (`src/test/java/com/circleguard/auth/`):
- `LoginControllerTest` — validación de endpoint `/api/v1/auth/login` con MockMvc
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

Implementadas con **Testcontainers** — levantan contenedores Docker reales de PostgreSQL durante el test, garantizando que el código interactúa con una base de datos real (no mocks).

### circleguard-auth-service (`AuthIntegrationTest.java`)

| Test | Descripción | Resultado esperado |
|------|-------------|-------------------|
| `healthEndpointReturnsUp` | GET /actuator/health con PostgreSQL real | HTTP 200 |
| `loginWithInvalidCredentialsReturns401` | POST /login con credenciales incorrectas | HTTP 4xx |
| `loginEndpointAcceptsJsonContentType` | POST /login verifica Content-Type de respuesta | application/json |

**Contenedor:** `postgres:15-alpine` con schema `circleguard_auth`

### circleguard-form-service (`FormIntegrationTest.java`)

| Test | Descripción | Resultado esperado |
|------|-------------|-------------------|
| `healthEndpointReturnsUp` | GET /actuator/health con PostgreSQL real | HTTP 200 |
| `questionnairesEndpointRequiresAuth` | GET /api/v1/questionnaires sin token | HTTP 401/403 |
| `healthSurveyEndpointRequiresAuth` | GET /api/v1/health-surveys sin token | HTTP 401/403 |

**Contenedor:** `postgres:15-alpine` con Flyway repair habilitado

**Ventaja vs mocks:** Los tests de integración detectan problemas reales de Flyway migrations, constraints de BD, y configuración de seguridad que los tests unitarios con H2 no detectarían.

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
| Gateway routing | Verificación que gateway enruta correctamente |
| Retry con reintentos | HTTPAdapter con retry para ambientes inestables |

**Ejecución:**
```bash
pip install requests pytest
pytest tests/e2e/ -v \
    --base-url=http://<STAGING_NODE_IP>:<PORT>
```

---

## 4. Pruebas de Rendimiento (Locust)

**Framework:** Locust
**Archivo:** `tests/performance/locustfile.py`
**Escenario:** Simulación de guardias de seguridad usando el sistema

### Comportamiento del usuario simulado
Cada `CircleGuardUser` ejecuta:
1. `on_start`: login con credenciales `staff_guard/password`
2. `@task(3)`: generar QR + validar acceso (flujo principal, peso 3x)
3. Tiempo de espera entre acciones: 1-3 segundos

### Métricas objetivo

| Métrica | Objetivo | Descripción |
|---------|----------|-------------|
| RPS (login) | > 10 req/s | Throughput del endpoint de autenticación |
| P95 latencia | < 500ms | 95% de requests bajo 500ms |
| P99 latencia | < 1000ms | 99% de requests bajo 1 segundo |
| Error rate | < 1% | Menos del 1% de requests fallando |
| Usuarios concurrentes | 50 | Carga representativa de turno escolar |

**Ejecución en K8s (CI):**
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

## 5. Análisis Estático de Código (SonarQube)

**Integración:** Stage `SonarQube Analysis` en Jenkinsfile (todas las ramas excepto prod)

### Métricas analizadas
- **Bugs:** Errores potenciales en el código
- **Vulnerabilidades:** Problemas de seguridad en código fuente
- **Code Smells:** Deuda técnica y malas prácticas
- **Duplicaciones:** Código duplicado entre servicios
- **Cobertura:** Integrado con reportes JaCoCo

**Quality Gate por defecto:**
- 0 bugs nuevos de severidad BLOCKER
- 0 vulnerabilidades nuevas
- Cobertura en código nuevo > 80%
- Duplicaciones < 3%

---

## 6. Escaneo de Vulnerabilidades en Contenedores (Trivy)

**Integración:** Stage `Trivy Scan` en Jenkinsfile después del Docker push

### Severidades escaneadas
- **HIGH** y **CRITICAL** únicamente (filtra LOW/MEDIUM para reducir ruido)

### Por qué Trivy
- Escanea capas del sistema operativo (Alpine, Ubuntu) + dependencias Java
- Detecta CVEs en librerías transitivas (ej: log4j, Spring Security)
- Salida en formato tabla legible + archivado en Jenkins

**Reportes:** Archivados como `trivy-<service-name>.txt` en cada build de Jenkins

---

## 7. Pruebas de Seguridad Web (OWASP ZAP)

**Herramienta:** OWASP ZAP Baseline Scan
**Integración:** Stage `OWASP ZAP Scan` en rama `staging`
**Target:** `circleguard-gateway-service` (único punto de entrada público)

### Tipo de scan: Baseline
El scan baseline ejecuta ataques pasivos (no destructivos), identificando:
- Headers de seguridad faltantes (X-Frame-Options, CSP, HSTS)
- Información expuesta en respuestas de error
- Configuraciones inseguras en endpoints públicos

**Reporte:** `zap-reports/zap-report.html` archivado en Jenkins

> Nota: El flag `-I` (ignore warnings) está configurado para que el pipeline no falle por findings de severidad INFO/LOW, enfocándose en MEDIUM+ como blockers futuros.

---

## 8. Cobertura de Código (JaCoCo)

**Integración:** Generado automáticamente al finalizar cada `./gradlew test`

**Reportes generados por servicio:**
```
services/<service>/build/reports/jacoco/test/
├── html/index.html    ← reporte visual
└── jacocoTestReport.xml ← para SonarQube
```

**Archivados en Jenkins:** `**/build/reports/jacoco/**`

Los reportes XML se integran con SonarQube para mostrar cobertura por clase, método y línea en el dashboard de calidad.
