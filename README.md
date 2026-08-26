# PetCare Enterprise

PetCare Enterprise es una plataforma de gestión para clínicas veterinarias. El proyecto comienza como un MVP académico y se diseña para evolucionar de forma gradual hacia un producto SaaS multi-tenant.

## Estado actual

Sprint 0 está técnicamente completo y HU-005 y HU-006 están implementadas. Se encuentran implementadas HU-001, HU-037, HU-002, HU-003, HU-039, HU-005 y HU-006: el monolito modular compilable, el entorno Docker reproducible, el registro protegido de tenants, la resolución confiable del contexto tenant, el contrato uniforme de errores y el ciclo local de sesión multi-tenant con JWT.

El backend dispone del endpoint protegido `POST /api/v1/platform/tenants`, del endpoint público mínimo `POST /api/v1/auth/login` y de `GET /api/v1/auth/session` para comprobar una sesión autenticada. Las migraciones inmutables V1 y V2 se complementan con V3, que incorpora la identidad, el RBAC mínimo y los eventos append-only de autenticación. La validación de HU-005 registra 77 pruebas aprobadas.

El frontend servido por Nginx ofrece el formulario responsive de acceso, validación de la sesión almacenada, cierre de sesión local, tratamiento accesible de expiración y un dashboard inicial mínimo. El access token se conserva exclusivamente en `sessionStorage` y su uso y rechazo están centralizados en el cliente HTTP. La administración de usuarios y permisos corresponde a HU-007 y HU-008.

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
| JWT | Access token firmado con HMAC SHA-256, issuer y expiración verificados por Spring Security |
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

## Autenticación de HU-005

El login acepta exclusivamente este cuerpo:

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "clinicSlug": "clinica-demo",
  "email": "admin@example.test",
  "password": "valor-local-no-real"
}
```

Una autenticación válida devuelve HTTP `200` con `accessToken`, `tokenType` (`Bearer`), `expiresAt` e `identity`. La identidad contiene los UUID confiables de usuario, tenant y sucursal primaria opcional, además de las autoridades efectivas obtenidas desde PostgreSQL. El token incluye `sub`, `iss`, `iat`, `exp`, `jti`, `uid`, `tid`, `bid` cuando existe y `authorities`.

Las credenciales rechazadas devuelven HTTP `401` con el contrato uniforme y el código estable `INVALID_CREDENTIALS`; la respuesta no distingue clínica, usuario, contraseña ni estado incorrectos. La validación de entrada continúa devolviendo HTTP `400` mediante el mismo contrato. En ambos casos se conserva el correlation ID.

La configuración JWT se recibe desde el entorno:

- `PETCARE_JWT_SECRET`: obligatorio y de al menos 32 bytes; nunca debe versionarse un secreto real.
- `PETCARE_JWT_ISSUER`: URI absoluta del emisor; valor local sugerido `https://petcare.local`.
- `PETCARE_JWT_ACCESS_TOKEN_TTL`: duración ISO-8601 positiva, con máximo de una hora; el valor local es `PT15M`.

V3 crea `users`, `roles`, `permissions`, `user_roles`, `role_permissions`, `user_branches` y `authentication_events`. Los roles pertenecen a un tenant, los permisos son globales y las relaciones poseen restricciones tenant-aware. Cada intento de login registra un evento independiente y append-only, incluso si el tenant o usuario no pudo resolverse.

El bootstrap interno está desactivado por defecto. Para habilitarlo se usa `PETCARE_BOOTSTRAP_ENABLED=true` junto con todas las variables `PETCARE_BOOTSTRAP_*` del archivo `.env.example`. No tiene endpoint, no contiene credenciales predeterminadas, reutiliza el registro de tenant de HU-002, genera el hash BCrypt inmediatamente y es idempotente: no duplica asociaciones ni cambia la contraseña de un usuario existente.

## Sesión y expiración de HU-006

Al cargar la aplicación, el frontend no confía en claims decodificados localmente: si existe un access token muestra una vista de validación y consulta `GET /api/v1/auth/session` antes de exponer el dashboard. Sin token muestra el login sin realizar esa solicitud.

El botón **Cerrar sesión** elimina inmediatamente el token de `sessionStorage` y la identidad mantenida en memoria, vacía la contraseña y vuelve al login sin mostrar un aviso de expiración. La operación es local e idempotente y no requiere un endpoint backend.

Un `401` recibido por una solicitud autenticada elimina la sesión local y muestra un mensaje de sesión expirada con el correlation ID cuando está disponible. El `401 INVALID_CREDENTIALS` del login no activa este flujo. Un `403`, un error de red o una respuesta inválida no eliminan el token; durante un fallo de validación el dashboard permanece oculto y se ofrece un reintento seguro.

El logout no revoca el JWT en el servidor. Como el sistema utiliza access tokens stateless de corta duración y no implementa refresh tokens, el token previamente emitido seguirá siendo criptográficamente válido hasta expirar. No existen blacklist, Redis, tabla de revocación ni endpoint ficticio de logout.

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

Flyway 11.7.2 valida y aplica las migraciones en PostgreSQL 18, aunque muestra una advertencia porque esa versión de PostgreSQL aún no figura entre las oficialmente comprobadas. `V1__verify_flyway.sql`, `V2__create_tenancy_and_audit.sql` y `V3__create_identity_and_authentication_events.sql` ya fueron validadas y deben tratarse como inmutables; cualquier cambio posterior debe realizarse mediante una nueva migración versionada.

El repositorio no contiene credenciales reales. Los valores de `.env.example` son ficticios y cualquier secreto local debe mantenerse únicamente en archivos ignorados por Git.

Detén y elimina los contenedores y redes sin borrar el volumen de PostgreSQL:

```powershell
docker compose down
```

No uses `docker compose down -v` si deseas conservar los datos locales.
