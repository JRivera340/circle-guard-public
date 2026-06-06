# Lineamientos Generales para Diagramas

Este documento establece cómo deben construirse los diagramas a lo largo de los entregables del Taller Final.

## 1. Herramienta y Formato
Todos los diagramas deben estar diseñados de forma que sean versionables o fácilmente incrustables.
*   **Preferencia:** `Mermaid.js` (Permite renderizar directamente en GitHub y Markdown).
*   **Alternativa:** `Draw.io` (Si el diagrama es muy complejo, se debe exportar la imagen a la carpeta `/docs/assets/` y mantener el XML de origen).

## 2. Arquitectura de Microservicios
El diagrama de arquitectura debe ilustrar:
- Los diferentes **servicios de Spring Boot** (Promotion, Auth, Gateway, etc.).
- Las bases de datos utilizadas y qué microservicio es el único dueño de cada una (ej. Neo4j para Promotion, PostgreSQL para Auth).
- El **Message Broker (Kafka)** que comunica de forma asíncrona los eventos entre microservicios.
- Las flechas deben ser direccionales e indicar si la llamada es síncrona (REST/gRPC) o asíncrona (Eventos).

## 3. Infraestructura y Despliegue (Kubernetes / AWS)
El diagrama de infraestructura generado por código debe incluir:
- **Redes:** VPCs, Subnets Públicas y Privadas.
- **Capa de Computo:** Nodos de EKS / Minikube, Ingress Controllers.
- **Seguridad:** Zonas de aislamiento, TLS endpoints.

*(Cualquier diagrama a incluirse en el repositorio debe apegarse a esta estructura).*

---

## 4. Diagrama de Arquitectura de Microservicios

```mermaid
flowchart TB
    Client[App Móvil / Web]

    subgraph edge[Borde]
        IG[Ingress NGINX + TLS]
        GW[gateway-service :8087]
    end

    subgraph svc[Microservicios Spring Boot - Java 17]
        AUTH[auth-service :8180]
        ID[identity-service :8083]
        FORM[form-service :8086]
        PROMO[promotion-service :8088]
        NOTIF[notification-service :8082]
        DASH[dashboard-service]
        FILE[file-service]
    end

    subgraph data[Datos - Database per Service]
        PG[(PostgreSQL)]
        NEO[(Neo4j)]
        REDIS[(Redis)]
        LDAP[(OpenLDAP)]
    end

    KAFKA{{Kafka}}

    Client -->|HTTPS| IG --> GW
    GW -->|REST| AUTH
    GW -->|lee estado| REDIS
    AUTH -->|REST| ID
    AUTH --> PG
    AUTH --> LDAP
    FORM --> PG
    ID --> PG
    ID --> NEO
    PROMO --> NEO
    PROMO --> REDIS

    FORM -.->|survey.submitted| KAFKA
    KAFKA -.->|consume| PROMO
    PROMO -.->|status.changed / alert.priority / circle.fenced| KAFKA
    KAFKA -.->|consume| NOTIF

    classDef async stroke-dasharray: 5 5;
```

> Flechas continuas = llamadas síncronas (REST). Flechas punteadas = eventos asíncronos vía Kafka.
> Cada servicio es dueño exclusivo de su almacén (patrón *Database per Service*).

## 5. Diagrama de Infraestructura y Despliegue

```mermaid
flowchart TB
    subgraph gcp[GCP - provisionado con Terraform]
        subgraph vpc[VPC circleguard - subnet 10.0.0.0/20]
            subgraph gke[GKE Cluster - node pool autoescalable e2-standard]
                subgraph nsapp[ns: circleguard-dev / staging / prod]
                    direction LR
                    PODS[Pods microservicios<br/>RBAC + NetworkPolicy deny-all]
                end
                subgraph nsmon[ns: monitoring]
                    PROM[Prometheus] --- GRAF[Grafana] --- AM[Alertmanager]
                end
                subgraph nslog[ns: logging]
                    ES[Elasticsearch] --- LS[Logstash] --- KB[Kibana]
                    FB[Filebeat DaemonSet]
                end
                subgraph nstrace[ns: tracing]
                    JAEGER[Jaeger]
                end
                INGRESS[Ingress NGINX + cert-manager TLS]
            end
        end
        GCS[(GCS backend<br/>estado Terraform remoto)]
    end

    Internet((Internet)) -->|HTTPS 443| INGRESS --> PODS
    PODS -->|/actuator/prometheus| PROM
    FB -->|logs| LS --> ES --> KB
    PODS -->|spans Zipkin| JAEGER
```

> El estado de Terraform vive en un backend remoto GCS, separado por *prefix* (dev/stage/prod).
> Las zonas de aislamiento se implementan con NetworkPolicies `default-deny` por namespace y RBAC por servicio.
