# PetCare Enterprise

PetCare Enterprise es una plataforma de gestión para clínicas veterinarias. El proyecto comienza como un MVP académico y se diseña para evolucionar de forma gradual hacia un producto SaaS multi-tenant.

## Estado actual

El repositorio se encuentra en Sprint 0. La HU-001 incorpora un esqueleto técnico compilable del backend y la estructura del monolito modular; el frontend continúa reservado y vacío.

**Todavía no existe funcionalidad de negocio.** No hay entidades, casos de uso, controladores, endpoints propios, migraciones, contenedores ni frontend implementado. `compose.yaml` se mantiene vacío deliberadamente.

## Alcance resumido del MVP

El MVP debe demostrar el flujo operativo principal de una clínica:

```text
Inicio de sesión
→ registro de propietario y paciente
→ programación de una cita
→ consulta veterinaria
→ diagnóstico y prescripción
→ movimiento asociado de inventario
→ auditoría y alertas
```

El alcance incluye clínicas y sucursales, autenticación, roles y permisos, propietarios, pacientes, citas, historia clínica, consultas, diagnósticos, prescripciones, productos, lotes, movimientos de inventario, alertas, dashboard básico y auditoría.

Quedan fuera del MVP las integraciones reales con WhatsApp y SUNAT, pagos y suscripciones, portal del propietario, aplicación móvil nativa, punto de venta completo, hospitalización y laboratorio avanzados, microservicios, Kafka, Kubernetes, BI avanzado e inteligencia artificial.

## Arquitectura prevista

- Monolito modular con principios de Clean Architecture.
- API REST JSON versionada bajo `/api/v1`.
- Módulos de `identity`, `tenancy`, `clinical`, `appointments`, `inventory`, `notifications`, `reporting` y `audit`.
- Una base PostgreSQL con esquema compartido y aislamiento lógico mediante tenant.
- Autorización RBAC normalizada y validada en el backend.
- Frontend responsive y orientado a tareas con HTML5, Bootstrap 5 y JavaScript.
- Migraciones versionadas, pruebas unitarias y pruebas de integración con PostgreSQL.

## Decisiones técnicas aprobadas

| Área | Decisión |
|---|---|
| Java | Eclipse Temurin 17 LTS |
| Backend | Spring Boot 3.5.16 |
| Build | Maven con Maven Wrapper |
| Migraciones | Flyway |
| Paquete base | `com.petcare.enterprise` |
| Base de datos | PostgreSQL 18 |
| Entorno local | Docker Compose |
| Login multi-tenant | `clinicSlug + email + password` |
| Unicidad de correo | Único dentro de cada tenant |
| Contexto de tenant | `TenantContext` desde Sprint 0 |
| Auditoría | Base append-only desde Sprint 0 |
| JWT | Autenticación real en Sprint 1 |
| Administración de BD | pgAdmin opcional mediante perfil de desarrollo |
| Frontend | HTML5, Bootstrap 5 y JavaScript moderno |
| Node.js | No se utilizará salvo necesidad futura justificada |

## Requisitos del entorno

- Git.
- JDK Eclipse Temurin 17 LTS.
- Maven Wrapper incluido en el backend; no es necesario instalar Maven globalmente para compilar y probar el proyecto.
- Docker Desktop con Docker Compose.
- Un editor o IDE con soporte para Java.

No es necesario instalar PostgreSQL localmente. Cuando exista configuración ejecutable, la base de datos se levantará mediante Docker Compose.

## Estructura actual

```text
petcare-enterprise/
├── backend/          # esqueleto Spring Boot y módulos del backend
├── docs/             # documentación funcional y técnica
├── frontend/         # reservado; actualmente vacío
├── .env.example      # nombres y valores ficticios de configuración local
├── .gitignore
├── AGENTS.md         # reglas permanentes para agentes de desarrollo
├── compose.yaml      # reservado; actualmente vacío
└── README.md
```

## Documentación

La documentación fuente se encuentra en [`docs/`](docs/). Antes de planificar o implementar una historia deben revisarse `AGENTS.md`, la preparación para desarrollo y las notas relevantes para el módulo afectado.

## Backend

Desde PowerShell, entra al backend:

```powershell
cd backend
```

Comprueba la versión del Maven Wrapper:

```powershell
.\mvnw.cmd --version
```

Ejecuta las pruebas, incluidas las reglas de arquitectura:

```powershell
.\mvnw.cmd test
```

Genera el paquete sin repetir las pruebas:

```powershell
.\mvnw.cmd package -DskipTests
```

Docker Desktop y PostgreSQL no son necesarios para ejecutar las pruebas de HU-001. La aplicación está configurada para usar PostgreSQL en un entorno real, pero esa infraestructura se incorporará en cambios posteriores.
