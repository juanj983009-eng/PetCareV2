# PetCare Enterprise

En Propietarios, `GET /api/v1/owners` admite `query` ausente, vacío o solo espacios para listar todos los registros del tenant autenticado. Un filtro no vacío exige al menos dos caracteres; se conserva `OWNER_READ`. El frontend consulta solo al pulsar Buscar y pagina de seis en seis.

PetCare Enterprise es una plataforma de gestión para clínicas veterinarias. El proyecto comienza como un MVP académico y se diseña para evolucionar de forma gradual hacia un producto SaaS multi-tenant.

## Estado actual

Sprint 0 está técnicamente completo y HU-005 a HU-022 están implementadas junto con el sistema visual Clínica Editorial (`UI-001`), la landing pública de presentación con Spring MVC y Thymeleaf (`UI-002`) y la integración del espacio de trabajo clínico responsive (`UI-003`). Se encuentran implementadas HU-001, HU-037, HU-002, HU-003, HU-039, UI-001, UI-002, UI-003 y HU-005 a HU-022: el monolito modular compilable, el entorno Docker reproducible, el registro protegido de tenants, la resolución confiable del contexto tenant, el contrato uniforme de errores, el ciclo local de sesión multi-tenant con JWT, la gestión tenant-aware de usuarios, la configuración controlada de roles y permisos, la búsqueda, registro y edición de propietarios, el registro, búsqueda paginada y edición de pacientes, la consulta del perfil clínico del paciente, la consulta de la agenda de citas, la creación de citas tenant-aware desde agenda y perfil del paciente, la confirmación o registro de llegada de citas (`HU-017`), la reprogramación o cancelación de citas en agenda (`HU-018`), el inicio de consultas clínicas desde cita (`HU-020`), el registro de evaluación clínica con constantes vitales estructuradas (`HU-021`), el registro y edición de diagnósticos clínicos en borrador (`HU-022`), la integración de la interfaz clínica responsive en proporción 75/25 (`UI-003`) y la página pública de presentación institucional servida por Thymeleaf (`GET /presentacion`).

El backend dispone del endpoint protegido `POST /api/v1/platform/tenants`, del endpoint público mínimo `POST /api/v1/auth/login`, de `GET /api/v1/auth/session` para comprobar una sesión autenticada, de `/api/v1/users` y `/api/v1/roles` para administrar identidad, de `/api/v1/owners` (`GET` con `OWNER_READ`, `POST` con `OWNER_CREATE` y `PUT /api/v1/owners/{ownerId}` con `OWNER_UPDATE`), de `/api/v1/patients` (`POST` con `PATIENT_CREATE`, `GET` con `PATIENT_READ`, `PUT /api/v1/patients/{patientId}` con `PATIENT_UPDATE` y `GET /api/v1/patients/{patientId}` protegido por `PATIENT_READ`), de `GET /api/v1/appointments` y `GET /api/v1/appointments/options` protegidos por `APPOINTMENT_READ` (asignado a `ADMINISTRADOR`, `RECEPCIONISTA` y `VETERINARIO`; `ALMACEN` excluido), de `POST /api/v1/appointments` protegido por `APPOINTMENT_CREATE`, de `PATCH /api/v1/appointments/{appointmentId}/status` protegido por `APPOINTMENT_STATUS_UPDATE`, de `PUT /api/v1/appointments/{appointmentId}/schedule` protegido por `APPOINTMENT_RESCHEDULE` y de `PUT /api/v1/appointments/{appointmentId}/cancellation` protegido por `APPOINTMENT_CANCEL` (los permisos `APPOINTMENT_CREATE`, `APPOINTMENT_STATUS_UPDATE`, `APPOINTMENT_RESCHEDULE` y `APPOINTMENT_CANCEL` pertenecen únicamente a `ADMINISTRADOR` y `RECEPCIONISTA`) dentro del tenant autenticado. El contrato de `POST /api/v1/appointments` exige como obligatorios `patientId`, `branchId`, `veterinarianId`, `startsAtLocal`, `endsAtLocal` y `appointmentType` (tipos admitidos: `CONSULTA_GENERAL`, `VACUNACION`, `CONTROL`, `URGENCIA`, `CIRUGIA` y `DESPARASITACION`), siendo `reason` y `notes` opcionales; las horas locales se convierten a instantes UTC usando la zona IANA de la sede, la cita nace siempre en estado `PROGRAMADA` y el veterinario debe estar activo, poseer el rol `VETERINARIO` y contar con asignación explícita a la sede en `user_branches`. En `PATCH /api/v1/appointments/{appointmentId}/status`, las transiciones permitidas son `PROGRAMADA → CONFIRMADA`, `PROGRAMADA → EN_ESPERA` y `CONFIRMADA → EN_ESPERA`; repetir el estado actual (`CONFIRMADA → CONFIRMADA` o `EN_ESPERA → EN_ESPERA`) es idempotente y las transiciones o destinos no autorizados devuelven `409 INVALID_APPOINTMENT_STATUS_TRANSITION`. En `PUT /api/v1/appointments/{appointmentId}/schedule` se permite reprogramar desde `PROGRAMADA` y `CONFIRMADA` (una cita `CONFIRMADA` vuelve a `PROGRAMADA` al reprogramarse). En `PUT /api/v1/appointments/{appointmentId}/cancellation` se permite cancelar desde `PROGRAMADA`, `CONFIRMADA` y `EN_ESPERA`, exigiendo motivo obligatorio y sin eliminar nunca la cita. Las migraciones inmutables V1–V13 se complementan con V14 (`V14__reschedule_and_cancel_tenant_appointments.sql`), última migración aplicada, que añade los metadatos de cancelación (`cancellation_reason`, `cancelled_at`, `cancelled_by`), migra filas `CANCELADA` heredadas e incorpora los permisos `APPOINTMENT_RESCHEDULE` y `APPOINTMENT_CANCEL`. Las operaciones efectivas persisten en la misma transacción el historial en `appointment_status_history` y las auditorías `APPOINTMENT_RESCHEDULED` y `APPOINTMENT_CANCELLED` en `audit_entries`, publicando los eventos internos `AppointmentRescheduled` y `AppointmentCancelled` procesados `AFTER_COMMIT`. La concurrencia de reprogramación y cancelación se resuelve mediante compare-and-set: las operaciones idénticas son idempotentes (`200 OK`) y los cambios incompatibles devuelven `409 APPOINTMENT_CONCURRENT_MODIFICATION`. Adicionalmente, el backend expone la landing pública `GET /presentacion` (con redirección canónica desde `/presentacion/`) renderizada con Spring Web MVC, Thymeleaf (`Model`, `th:text`, `th:each`, `th:href`, `th:replace` y fragmentos reutilizables), Bootstrap 5.3.3 y el sistema visual Clínica Editorial, sin consultar PostgreSQL ni `TenantContext` y sin modificar las API REST; únicamente `/presentacion`, `/presentacion/` y `/presentacion-assets/**` son públicos en `SecurityConfiguration`, mientras todas las rutas `/api/v1/**` conservan su protección JWT y RBAC. La suite automatizada cuenta con 134 pruebas de backend (incluidas 23 pruebas de integración de `appointments` y 4 pruebas de `PublicLandingControllerTest`) y en frontend con 11 suites y 96 casos de arnés aprobados.

El frontend clínico servido por Nginx en `/` incorpora el espacio de trabajo clínico editorial (UI-003) con barra lateral plegable y navegación unificada (Inicio, Agenda, Pacientes, Propietarios, Usuarios, Roles y permisos), encabezado responsive con identidad contextual, inicio clínico con tarjetas operativas y acciones rápidas según permisos, y el workspace de consulta médica estructurado en proporción 75/25 (atención clínica y constantes vitales / panel contextual del paciente). El encabezado proyecta el slug de la clínica y el correo del usuario ingresados durante el login como referencia visual en la sesión activa; los permisos efectivos, la identidad autorizada y el aislamiento tenant continúan procediendo de forma estricta y autoritativa desde el backend. Se conservan el formulario responsive de acceso, validación de la sesión almacenada, cierre de sesión local, tratamiento accesible de expiración, un dashboard inicial y las áreas operativas y administrativas responsive de usuarios, roles, propietarios (incluyendo edición con `OWNER_UPDATE`), padrón de pacientes, perfil clínico del paciente (identificación, propietario, alergias/alertas médicas, historia clínica base, diagnósticos previos con `HU-022`, edición con `PATIENT_UPDATE` y acceso directo a nueva cita con paciente preseleccionado) y agenda de citas por fecha, sucursal y veterinario (tabla cronológica desde 768 px, tarjetas móviles debajo de 768 px, modal de creación de citas para quienes poseen `APPOINTMENT_CREATE`, acciones de confirmación y registro de llegada con `APPOINTMENT_STATUS_UPDATE`, reprogramación en modal con `APPOINTMENT_RESCHEDULE` y cancelación con motivo en modal con `APPOINTMENT_CANCEL`, preservando los filtros activos), manteniendo los estados vacíos honestos para consultas, vacunas y prescripciones mientras continúan pendientes de sus historias de Sprint 2. En paralelo, Nginx publica la landing de presentación en `http://localhost:8081/presentacion` (y `http://localhost:18081/presentacion` en el entorno de demo), la cual distingue explícitamente los módulos ya implementados (autenticación multi-tenant, usuarios/roles/permisos, propietarios `HU-009 · HU-010 · HU-014`, pacientes y perfil clínico básico `HU-011 · HU-012 · HU-013 · HU-014`, agenda/citas `HU-015` a `HU-018`, y consultas/diagnósticos clínicos `HU-020` a `HU-022`) de los módulos marcados como “Próximamente” (prescripciones, inventario/lotes/alertas y reportes avanzados), enlazando de regreso al login clínico en `/`. El access token se conserva exclusivamente en `sessionStorage` y su uso y rechazo están centralizados en el cliente HTTP.

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

## Gestión de usuarios de HU-007

La API tenant-aware de usuarios se encuentra bajo `/api/v1/users`. `USER_READ` permite listar, buscar, filtrar, ordenar y consultar usuarios y las opciones de rol y sede. `USER_MANAGE` permite registrar, editar, activar y desactivar, siempre en combinación con el permiso de lectura. El rol `ADMINISTRADOR` recibe ambos permisos de forma idempotente; los roles continúan perteneciendo a cada tenant y los permisos forman un catálogo global.

El alta requiere nombres, correo, contraseña inicial, un rol del tenant y una sede del tenant. El correo se normaliza igual que en el login, es único dentro de la clínica y la contraseña se transforma inmediatamente con BCrypt. Las respuestas nunca incluyen el hash ni exponen entidades de persistencia. La edición no permite cambiar contraseñas y las transiciones de estado son explícitas e idempotentes; no existe borrado físico.

Cada creación, edición y cambio de estado genera auditoría append-only dentro de la misma transacción. Los cambios de rol y sede se registran también de forma explícita y los datos auditados excluyen contraseñas, hashes y tokens. V4 añade `updated_at` y `version` a `users`, junto con índices de búsqueda y los permisos `USER_READ` y `USER_MANAGE`.

La desactivación impide nuevos inicios de sesión, pero no revoca access tokens ya emitidos: seguirán siendo válidos hasta su expiración corta. HU-007 no incorpora refresh tokens, blacklist, Redis ni revocación server-side.

## Roles y permisos de HU-008

V5 asegura idempotentemente, para tenants existentes y para cada tenant registrado después de la migración, cuatro roles de sistema tenant-aware: `ADMINISTRADOR`, `VETERINARIO`, `RECEPCIONISTA` y `ALMACEN`. No existen roles globales, plantillas ni CRUD libre de roles. El catálogo de permisos es global y solo se concede mediante esos roles del tenant.

| Rol | Permisos iniciales |
|---|---|
| `ADMINISTRADOR` | `DASHBOARD_VIEW`, `USER_READ`, `USER_MANAGE`, `ROLE_READ`, `ROLE_MANAGE` |
| `VETERINARIO` | `DASHBOARD_VIEW` |
| `RECEPCIONISTA` | `DASHBOARD_VIEW` |
| `ALMACEN` | `DASHBOARD_VIEW` |

`ROLE_READ` permite consultar la configuración y `ROLE_MANAGE` actualizar permisos y reemplazar las asignaciones de roles de un usuario. La API no acepta tenant, permisos ni autoridades como fuente de identidad: todo se limita al `TenantContext` autenticado. No hay endpoints de eliminación; el administrador no puede perder las autoridades mínimas de administración ni puede quedar eliminado, desactivado o sin rol cuando es el último administrador activo. Cada cambio de permisos o roles genera auditoría append-only atómica, sin credenciales ni tokens. Las autoridades modificadas se reflejan en el siguiente login o JWT emitido.

## Diagnósticos clínicos de HU-022

HU-022 habilita el registro y edición de múltiples diagnósticos dentro de una consulta clínica en estado `BORRADOR`, así como su consulta consolidada desde la historia clínica del paciente:

- `GET /api/v1/consultations/{consultationId}/diagnoses`: lista los diagnósticos de una consulta (protegido por `CONSULTATION_READ`, asignado al rol de sistema `VETERINARIO`).
- `POST /api/v1/consultations/{consultationId}/diagnoses`: añade un diagnóstico a la consulta en borrador (protegido por `CONSULTATION_UPDATE`, restringido al veterinario asignado o administrador).
- `PUT /api/v1/consultations/{consultationId}/diagnoses/{diagnosisId}`: actualiza un diagnóstico existente en borrador (protegido por `CONSULTATION_UPDATE`).
- `GET /api/v1/patients/{patientId}/diagnoses`: lectura histórica de diagnósticos del paciente para el perfil clínico (protegido por `PATIENT_READ`).

El modelo relacional persistido en `consultation_diagnoses` (migración `V17__create_tenant_consultation_diagnoses.sql`) cuenta con aislamiento tenant estricto, tipo `PRESUNTIVO` o `DEFINITIVO`, código CIE opcional (máx. 50 caracteres), descripción obligatoria (máx. 300 caracteres) y notas opcionales (máx. 2000 caracteres). La concurrencia entre mutaciones de diagnósticos y cambios de estado de la consulta se coordina transaccionalmente mediante bloqueo pesimista (`SELECT ... FOR UPDATE`), serializando escrituras y garantizando atomicidad con la auditoría append-only (`CONSULTATION_DIAGNOSIS_ADDED` y `CONSULTATION_DIAGNOSIS_UPDATED`). El frontend en `index.html` correlaciona respuestas con la consulta y paciente activos para evitar condiciones de carrera, distingue errores de carga de listas vacías y permite añadir y editar diagnósticos sin papelera ni eliminación prematura.

## Espacio de trabajo e interfaz clínica de UI-003

UI-003 integra el sistema visual editorial en un shell clínico responsive y operativo:

- **Navegación y shell clínico:** barra lateral compacta y plegable en escritorio con navegación unificada (Inicio, Agenda, Pacientes, Propietarios, Usuarios, Roles y permisos) y menú colapsable adaptado a dispositivos móviles sin desbordamiento horizontal a 360 y 390 px.
- **Espacio de trabajo de consulta (75/25):** distribución clínica optimizada donde el 75% del área se destina al registro estructurado de la atención médica (constantes fisiológicas con unidades explícitas, motivo, anamnesis, examen físico general y diagnósticos presuntivos/definitivos) y el 25% a la tarjeta contextual del paciente y resumen de estado de la consulta.
- **Inicio clínico operativo:** la pantalla principal prioriza el acceso directo a la agenda, padrón de pacientes y búsqueda de propietarios, incorporando botones rápidos de alta condicionada a permisos (`OWNER_CREATE` y `PATIENT_CREATE`). La lista técnica de códigos de autoridades RBAC se mantiene accesible en un bloque secundario colapsable (`<details>`).
- **Identidad y honestidad de sesión:** el encabezado muestra el identificador de la clínica y el correo del usuario suministrados durante el inicio de sesión como información contextual visual en la sesión activa. La seguridad, autoridades efectivas y resolución tenant provienen estrictamente de los contratos del backend (`/api/v1/auth/session`). Los indicadores de estado reflejan con veracidad una sesión autenticada activa sin simular turnos hospitalarios ni disponibilidad ficticia.

## Prescripciones médicas externas de HU-023 (Primera parte)

HU-023 habilita la emisión y consulta de prescripciones médicas con medicamentos externos al catálogo dentro de una consulta clínica en estado `BORRADOR`:

- `GET /api/v1/consultations/{consultationId}/prescriptions`: lista las prescripciones emitidas en la consulta (protegido por `CONSULTATION_READ`, asignado al rol de sistema `VETERINARIO`).
- `POST /api/v1/consultations/{consultationId}/prescriptions`: emite una nueva prescripción vinculada a la consulta (protegido por `CONSULTATION_UPDATE`, restringido al veterinario asignado a la consulta).

### Emisión y estructura de datos

La emisión admite una o múltiples líneas de medicamentos externos. Cada línea registra obligatoriamente:
- Medicamento en texto libre (máx. 200 caracteres).
- Dosis (máx. 100 caracteres).
- Frecuencia (máx. 100 caracteres).
- Duración (máx. 100 caracteres).
- Vía de administración opcional (máx. 50 caracteres).
- Indicaciones específicas opcionales por medicamento (máx. 2000 caracteres).

A nivel de prescripción, se admiten indicaciones generales opcionales para el propietario (máx. 2000 caracteres).

El modelo persistido en `prescriptions` y `prescription_items` (migración `V18__create_tenant_prescriptions.sql`) garantiza aislamiento tenant, folios secuenciales por tenant mediante UUID v4, y auditoría transaccional append-only (`PRESCRIPTION_ISSUED`).

### Idempotencia y control de concurrencia

El endpoint `POST /prescriptions` exige una clave de idempotencia (`idempotencyKey`, máx. 100 caracteres):
- Si se envía la **misma clave con el mismo contenido** (medicamentos, dosis, pautas, vía, indicaciones y orden de líneas normalizados), el sistema recupera la emisión original sin duplicar registros en base de datos ni emitir nuevos eventos de auditoría.
- Si se envía la **misma clave con contenido diferente**, el sistema rechaza la solicitud de manera estable con `409 CONFLICT` (`CONFLICT`).
- El servicio valida el tenant y que el usuario actuante sea el veterinario asignado antes de resolver cualquier solicitud idempotente, evitando accesos no autorizados mediante reutilización de claves.
- La concurrencia se coordina transaccionalmente mediante bloqueo pesimista tenant-aware (`SELECT ... FOR UPDATE`) sobre la consulta padre.
- En el frontend, las respuestas perdidas o errores de red conservan la clave en el formulario para reintentar de forma segura; la edición posterior de campos no genera claves automáticas y los errores 409 por idempotencia no inhabilitan la evaluación general de la consulta.

### Limitaciones de la entrega

Esta entrega implementa exclusivamente prescripciones con medicamentos externos al catálogo, sin enlace a almacenes ni dispensación de stock. Quedan diferidos para las siguientes fases de HU-023 (junto al módulo de inventario):
- Selección de productos desde el catálogo centralizado del tenant.
- Validación de existencias y reserva de stock por sede.
- Asignación de lotes vigentes bajo política FEFO.
- Generación de movimientos automáticos de consumo/salida en inventario.

## Finalización de consultas de HU-024

HU-024 habilita la finalización formal de la atención veterinaria vinculada a una consulta:

- `POST /api/v1/consultations/{consultationId}/finalize`: finaliza una consulta en borrador (protegido por `CONSULTATION_FINALIZE`, restringido exclusivamente al veterinario asignado).
- `GET /api/v1/appointments/{appointmentId}/consultation`: consulta la información clínica vinculada a una cita (protegido por `CONSULTATION_READ`, asignado al rol de sistema `VETERINARIO`), permitiendo abrir la consulta en modo de solo lectura desde la agenda cuando la cita se encuentra en estado `FINALIZADA`.

### Reglas de negocio y consistencia transaccional

- **Validación clínica mínima**: se valida que el motivo (`reason`) esté registrado y no quede en blanco antes de autorizar la finalización. No se imponen diagnósticos definitivos o recetas cuando no aplican al cuadro clínico.
- **Transición atómica y aislamiento modular**: la consulta transiciona de `BORRADOR` a `FINALIZADA` y la cita vinculada pasa de `EN_ATENCION` a `FINALIZADA` dentro del mismo límite transaccional atómico mediante el puerto público `AppointmentConsultationGateway`.
- **Coordinación de concurrencia**: se adquiere un bloqueo pesimista en base de datos (`lockConsultationForUpdate`) para serializar escrituras concurrentes (evaluación, diagnósticos, recetas y finalización). Una vez finalizada, cualquier intento de mutación clínica posterior es rechazado con error `409 INVALID_CONSULTATION_STATUS`.
- **Idempotencia**: peticiones repetidas de finalización sobre una consulta ya finalizada devuelven el estado persistido de forma idempotente sin duplicar auditorías ni eventos.
- **Auditoría y eventos de dominio**: se registra la entrada inmutable `CONSULTATION_FINALIZED` en la auditoría del tenant y se publica el evento de dominio `ConsultationFinalized` procesado de forma desacoplada y segura en la fase `AFTER_COMMIT`.
- **Frontend y modo de solo lectura**: incluye diálogo accesible de confirmación, bloqueo estricto si existen modificaciones locales no guardadas en pantalla (evaluación, diagnósticos o prescripción), actualización inmediata del badge a `FINALIZADA` e inhabilitación completa de controles de edición.

## Historia clínica cronológica de HU-025

HU-025 habilita la consulta cronológica de la historia clínica de un paciente desde su perfil:

- `GET /api/v1/patients/{patientId}/consultations?page=0&size=20`: consulta paginada de las atenciones y consultas del paciente, ordenadas de la más reciente a la más antigua (`c.started_at DESC, c.id DESC`).
  - **Aislamiento y seguridad**: requiere la autoridad `CONSULTATION_READ` (asignada al rol de sistema `VETERINARIO`). Valida que el paciente pertenezca estrictamente al tenant autenticado; si no existe o pertenece a otro tenant, responde `404 NOT_FOUND` para no revelar la existencia de recursos ajenos. Un usuario sin `CONSULTATION_READ` recibe `403 FORBIDDEN`.
  - **Eficiencia y optimización de consultas**: paginación por lotes en exactamente 3–4 consultas SQL (`COUNT`, página acotada de consultas con `LEFT JOIN appointments` para resolución de motivo `COALESCE(c.reason, a.reason)`, diagnósticos con cláusula `IN` y prescripciones con ítems con cláusula `IN`), evitando problemas de N+1 y productos cartesianos que duplicarían registros paginados.
  - **Contenido resumido**: incluye ID de consulta, fecha/hora de inicio, estado (`BORRADOR`, `FINALIZADA`), profesional veterinario asignado, sucursal, motivo clínico, lista de diagnósticos (código CIE/texto, tipo y descripción) y resumen de prescripciones emitidas con medicamentos, dosis, pauta y vía.

### Interfaz clínica y navegación

- **Integración en Perfil de Paciente**: la pestaña/sección de "Historia clínica" sustituye la visualización plana anterior y presenta tarjetas cronológicas detalladas por consulta, conservando en cabecera el resumen de identificación del paciente, alertas médicas y alergias.
- **Acceso a detalle en solo lectura**: cada tarjeta provee la acción "Ver detalle" que abre la interfaz de evaluación clínica en modo de solo lectura (`readOnly: true`), inhabilitando controles de edición (guardar, finalizar, agregar diagnósticos o recetas) para consultas finalizadas o revisión histórica, mostrando de forma confiable las alertas médicas y alergias del paciente sin cruce entre historiales, y sin disparar mutaciones accidentales en el backend.
- **Retorno contextual**: la navegación permite volver al perfil del paciente preservando el paciente activo seleccionado y el estado de la página de historia.
- **Manejo de estados y resiliencia**: soporta estados visuales explícitos de carga (`#patient-profile-history-loading`), sin registros (`#patient-profile-history-empty`), error con identificador de correlación y botón de reintento (`#patient-profile-history-error`), y permiso denegado (`#patient-profile-history-forbidden`) ante falta de autoridad.

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

Flyway 11.7.2 valida y aplica las migraciones en PostgreSQL 18, aunque muestra una advertencia porque esa versión de PostgreSQL aún no figura entre las oficialmente comprobadas. `V1__verify_flyway.sql`, `V2__create_tenancy_and_audit.sql` y `V3__create_identity_and_authentication_events.sql` ya fueron validadas y deben tratarse como inmutables. V4 corresponde a HU-007 y quedará inmutable después de su aplicación validada; cualquier cambio posterior debe realizarse mediante una nueva migración versionada.

El repositorio no contiene credenciales reales. Los valores de `.env.example` son ficticios y cualquier secreto local debe mantenerse únicamente en archivos ignorados por Git.

Detén y elimina los contenedores y redes sin borrar el volumen de PostgreSQL:

```powershell
docker compose down
```

No uses `docker compose down -v` si deseas conservar los datos locales.

## Demo aislada

La demo usa un proyecto Compose y un volumen independientes: `petcare-demo` y
`petcare_demo_postgres_data`. Nunca usa `petcareenterprise_postgres_data`.

1. Copia `.env.demo.example` como `.env.demo` y completa localmente los valores
   secretos. `.env.demo` está ignorado por Git y no debe compartirse.
2. Levanta la demo: `./scripts/demo-up.ps1`.
3. Abre `http://localhost:18081` e inicia sesión con el slug, correo y contraseña
   definidos en tu `.env.demo`.
4. Muestra las autoridades y navegación; busca propietarios, registra un
   propietario ficticio y luego su paciente. El registro crea automáticamente la
   historia clínica y la auditoría asociada.
5. Cierra sesión y detén la demo conservando sus datos: `./scripts/demo-down.ps1`.

Consulta su estado con `./scripts/demo-status.ps1`. No hay un script de reinicio
destructivo: elimina manualmente el volumen de demo solo tras verificar su nombre
exacto y nunca uses comandos destructivos sobre el volumen local principal.
