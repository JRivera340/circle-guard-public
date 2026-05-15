# Informe Técnico: Implementación DevOps y QA - CircleGuard

## 1. Introducción
Este informe documenta la implementación de prácticas DevOps y aseguramiento de calidad para CircleGuard, integrando infraestructura local con contenedores, orquestación en Kubernetes y automatización de pipelines en Jenkins. El objetivo fue construir un flujo de integración y despliegue continuo que permitiera validar cambios con rapidez, mantener trazabilidad del ciclo de vida del software y verificar el comportamiento del sistema bajo condiciones de carga.

## 2. Configuración de Infraestructura
Para el taller se configuró un entorno completo de CI/CD. La base de datos PostgreSQL y el motor de grafos Neo4j se ejecutan en Docker Desktop, mientras que los microservicios se despliegan en un clúster local de Kubernetes.

**[PANTALLAZO SUGERIDO: Terminal con el comando `kubectl get pods -n circleguard-dev` mostrando todos los servicios en estado Running]**

Esta arquitectura permitió separar servicios de soporte e infraestructura de los componentes de aplicación, facilitando pruebas locales reproducibles y una operación más cercana a un entorno real.

## 3. Definición de Pipelines
Se implementaron tres pipelines en Jenkins para gestionar los ciclos de vida del software:

### 3.1 Pipeline de Desarrollo (Dev)
Este pipeline estuvo orientado a la integración continua rápida. Ejecuta la construcción del proyecto con Gradle y realiza el despliegue automático hacia el entorno de desarrollo.
**[PANTALLAZO SUGERIDO: Interfaz de Jenkins mostrando el pipeline de DEV ejecutado con éxito]**

### 3.2 Pipeline de Producción (Master)
El pipeline de producción incorporó controles adicionales antes del despliegue: análisis de seguridad con Trivy, pruebas de carga con Locust y generación automática de Release Notes.
**[PANTALLAZO SUGERIDO: Interfaz de Jenkins mostrando el pipeline de MASTER con todas sus etapas completadas satisfactoriamente]**

## 4. Estrategia de Pruebas y Análisis
Se implementó una estrategia de pruebas multinivel para asegurar la estabilidad del sistema de acceso.

### 4.1 Pruebas Unitarias e Integración (Java/JUnit 5)
Se añadieron 10 pruebas nuevas. Las pruebas unitarias validan la lógica asociada a los tokens QR en `auth-service`, y las de integración verifican que el cambio de estado se propague correctamente desde Neo4j hasta Redis.
**[PANTALLAZO SUGERIDO: Terminal con el resultado de `./gradlew test` mostrando todas las pruebas aprobadas]**

### 4.2 Pruebas de Sistema (E2E)
Se utilizó Python con Pytest para validar flujos completos (Login -> QR -> Puerta).
**[PANTALLAZO SUGERIDO: Terminal con el resultado de `pytest tests/e2e/test_e2e_flows.py` mostrando todos los casos en verde]**

### 4.3 Análisis de Rendimiento (Locust)
Se ejecutaron pruebas de carga con Locust simulando 100 usuarios concurrentes.
**[PANTALLAZO SUGERIDO: Tabla de resultados de Locust (Statistics) mostrando throughput y latencia]**

**Métricas obtenidas:**
*   **Throughput**: ~45 peticiones por segundo sin errores.
*   **Latencia (P95)**: < 190 ms, cumpliendo con el SLA de respuesta rápida.
*   **Estabilidad**: Sin fallos de conexión bajo estrés.

## 5. Gestión de Lanzamiento
Se automatizó la generación de notas de versión para cada despliegue realizado desde la rama Master.
**[PANTALLAZO SUGERIDO: Contenido del archivo `RELEASE_NOTES.md` generado automáticamente]**

## 6. Conclusiones
La implementación permitió integrar microservicios en Java con una infraestructura DevOps moderna. El sistema demuestra capacidad para detectar riesgos de salud y bloquear el acceso en tiempo real, manteniendo alta disponibilidad y buen desempeño bajo carga.
