# PetCare Enterprise

PetCare Enterprise es una plataforma de gestión para clínicas veterinarias. El proyecto comienza como un MVP académico y se diseña para evolucionar de forma gradual hacia un producto SaaS multi-tenant.

## Estado actual

Sprint 0 está técnicamente completo. Se encuentran implementadas HU-001, HU-037, HU-002, HU-003 y HU-039: el monolito modular compilable, el entorno Docker reproducible, el registro protegido de tenants, la resolución confiable del contexto tenant y el contrato uniforme de errores.

El backend dispone del endpoint protegido `POST /api/v1/platform/tenants`, de las tablas `tenants`, `branches` y `audit_entries`, y de las migraciones inmutables V1 y V2. La última evidencia conocida registra 56 pruebas aprobadas; no implica que se hayan ejecutado de nuevo al actualizar este documento.

El frontend sigue siendo una base técnica servida por Nginx, con proxy `/api/`, cliente HTTP y utilidades seguras para errores y formularios. Aún no existen login, JWT, usuarios persistidos ni RBAC persistido. HU-005 es la siguiente historia y será responsable de esas capacidades, de V3 y del bootstrap interno de las primeras credenciales.

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
| RBAC del MVP | Roles obligatoriamente por tenant y catálogo global de permisos |
| Primer administrador | Bootstrap interno desactivado por defecto y configurado por entorno |
| Eventos de autenticación | Tabla append-only separada; tenant opcional cuando el slug no se resuelve |
| Administración de BD | pgAdmin opcional mediante perfil de desarrollo |
| Frontend | HTML5, Bootstrap 5 y JavaScript moderno |
| Node.js | No se utilizará salvo necesidad futura justificada |

## Requisitos del entorno

- Git.
- JDK Eclipse Temurin 17 LTS.
- Maven Wrapper incluido en el backend; no es necesario instalar Maven globalmente para compilar y probar el proyecto.
- Docker Desktop con Docker Compose.
- Un editor o IDE con soporte para Java.

No es necesario instalar PostgreSQL ni Maven globalmente. PostgreSQL y los servicios de la aplicación se levantan mediante Docker Compose.

## Estructura actual

```text
petcare-enterprise/
├── backend/          # Spring Boot, módulos, Dockerfile y migraciones
├── docs/             # documentación funcional y técnica
├── frontend/         # shell técnico estático servido por Nginx
├── .env.example      # nombres y valores ficticios de configuración local
├── .gitignore
├── AGENTS.md         # reglas permanentes para agentes de desarrollo
├── compose.yaml      # PostgreSQL, backend y frontend
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

Docker Desktop debe estar iniciado para ejecutar la suite completa, porque las pruebas de integración levantan PostgreSQL mediante Testcontainers. No es necesario instalar PostgreSQL directamente en el equipo.

## Entorno local con Docker Compose

La configuración levanta tres servicios:

- `postgres`: PostgreSQL 18 con datos persistentes en un volumen nombrado y sin puerto publicado al host.
- `backend`: aplicación Spring Boot construida con Java 17 y Maven Wrapper; expone Actuator para comprobar su salud.
- `frontend`: Nginx con un shell técnico responsive, cliente HTTP, utilidades de formulario y proxy `/api/` hacia el backend.

Desde la raíz del repositorio, crea la configuración local a partir del ejemplo seguro:

```powershell
Copy-Item .env.example .env
```

Puedes cambiar los valores ficticios de desarrollo en `.env`. Este archivo está ignorado por Git y nunca debe contener credenciales de producción.

Valida y construye el entorno:

```powershell
docker compose config
docker compose build
```

Inicia los servicios en segundo plano:

```powershell
docker compose up -d
docker compose ps
```

Cuando los tres servicios estén saludables:

- Frontend: <http://localhost:8081>
- Salud del backend: <http://localhost:8080/actuator/health>

Los puertos pueden cambiarse mediante `FRONTEND_PORT` y `BACKEND_PORT` en `.env`. PostgreSQL solo es accesible dentro de la red privada de Compose.

Para revisar la ejecución de Flyway:

```powershell
docker compose logs backend
```

Flyway 11.7.2 valida y aplica las migraciones en PostgreSQL 18, aunque muestra una advertencia porque esa versión de PostgreSQL aún no figura entre las oficialmente comprobadas. `V1__verify_flyway.sql` y `V2__create_tenancy_and_audit.sql` ya fueron aplicadas y son inmutables; cualquier cambio posterior debe realizarse mediante una nueva migración versionada. V3 pertenecerá a HU-005 y todavía no existe.

El repositorio no contiene credenciales reales. Los valores de `.env.example` son ficticios y cualquier secreto local debe mantenerse únicamente en archivos ignorados por Git.

Detén y elimina los contenedores y redes sin borrar el volumen de PostgreSQL:

```powershell
docker compose down
```

No uses `docker compose down -v` si deseas conservar los datos locales.
