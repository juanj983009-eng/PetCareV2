package com.petcare.enterprise.shared.infrastructure.web.landing;

import java.util.List;

/**
 * Immutable, database-free presentation data model for the public PetCare Enterprise landing view.
 */
public final class PublicLandingViewModel {

    private PublicLandingViewModel() {
    }

    public record ValueProposition(
            String brandName,
            String folioCode,
            String eyebrow,
            String headline,
            String lead,
            String institutionalNote,
            String loginUrl,
            String presentationUrl,
            String stylesheetUrl) {
    }

    public record ProductPhase(
            String phaseCode,
            String phaseTitle,
            String summary,
            String verificationSummary,
            List<String> modalDeliverables,
            List<String> modalScopeBoundaries) {

        public ProductPhase {
            modalDeliverables = List.copyOf(modalDeliverables);
            modalScopeBoundaries = List.copyOf(modalScopeBoundaries);
        }
    }

    public record BenefitItem(
            String folio,
            String title,
            String summary,
            String architecturalPrinciple) {
    }

    public record ImplementedModuleItem(
            String folio,
            String name,
            String stories,
            String statusLabel,
            String description,
            List<String> highlights) {

        public ImplementedModuleItem {
            highlights = List.copyOf(highlights);
        }
    }

    public record UpcomingModuleItem(
            String folio,
            String name,
            String badgeLabel,
            String sprintTarget,
            String description) {
    }

    public record StackItem(
            String layer,
            String technology,
            String responsibility) {
    }

    public record NavigationLink(
            String label,
            String href) {
    }

    public static ValueProposition valueProposition() {
        return new ValueProposition(
                "PetCare Enterprise",
                "FOLIO UI-002 · ARQUITECTURA CLÍNICA",
                "Plataforma Multi-Tenant de Gestión Veterinaria",
                "Operación clínica veterinaria con aislamiento por clínica, agenda verificada y trazabilidad auditable",
                "PetCare Enterprise articula recepción, padrón de propietarios, expediente base de pacientes y agenda por sede en un monolito modular con Clean Architecture, diseñado como MVP universitario con base sólida para evolucionar a SaaS.",
                "Renderizado server-side mediante Spring MVC y Thymeleaf · Sin consultas a base de datos ni exposición de datos clínicos",
                "/",
                "/presentacion",
                "/presentacion-assets/css/presentacion.css");
    }

    public static ProductPhase productPhase() {
        return new ProductPhase(
                "MVP Universitario · Segundo Avance (Sprint 2)",
                "Base multi-tenant, seguridad RBAC, propietarios, pacientes y ciclo de agenda implementados",
                "El sistema cuenta con Sprint 0 completo y las historias HU-005 a HU-018 operativas: autenticación JWT por clínica, administración de usuarios y permisos, registro y edición de propietarios y pacientes, perfil clínico inicial y gestión de agenda con control de solapamientos y concurrencia.",
                "Persistencia versionada con Flyway (migraciones inmutables V1 a V14), tablas append-only para eventos de autenticación, historial de estados de citas y auditoría transversal en audit_entries.",
                List.of(
                        "Autenticación multi-tenant con clinicSlug + email + password y validación de sesión activa.",
                        "Autorización RBAC normalizada por clínica con roles ADMINISTRADOR, VETERINARIO, RECEPCIONISTA y ALMACEN.",
                        "Gestión completa de propietarios y pacientes con creación automática de historia clínica base.",
                        "Agenda por fecha, sucursal y veterinario con conversión horaria IANA y exclusión PostgreSQL btree_gist.",
                        "Confirmación, registro de llegada, reprogramación y cancelación con motivo mediante compare-and-set e historial inmutable."),
                List.of(
                        "Las consultas clínicas completas, diagnósticos y prescripciones se encuentran planificados como siguientes entregas de Sprint 2.",
                        "El control de inventario, lotes FEFO y alertas operativas corresponde al alcance de Sprint 3.",
                        "No se afirman certificaciones externas ni integraciones fuera del alcance actual del MVP."));
    }

    public static List<NavigationLink> navigationLinks() {
        return List.of(
                new NavigationLink("Beneficios", "#beneficios"),
                new NavigationLink("Módulos implementados", "#modulos-implementados"),
                new NavigationLink("Roadmap", "#roadmap-proximo"),
                new NavigationLink("Arquitectura y stack", "#arquitectura-stack"));
    }

    public static List<BenefitItem> benefits() {
        return List.of(
                new BenefitItem(
                        "01 · AISLAMIENTO",
                        "Frontera multi-tenant no negociable",
                        "Cada operación de negocio resuelve la clínica exclusivamente desde el contexto autenticado del servidor, impidiendo lecturas o modificaciones cruzadas entre clínicas.",
                        "TenantContext + claves foráneas compuestas tenant-aware en PostgreSQL"),
                new BenefitItem(
                        "02 · GOBERNANZA",
                        "Control de acceso por rol y permiso explícito",
                        "Las acciones clínicas y administrativas se validan en el backend según autoridades efectivas por rol, adaptando también la interfaz a los permisos de cada puesto.",
                        "Spring Security stateless + catálogo global de permisos asignados por clínica"),
                new BenefitItem(
                        "03 · INTEGRIDAD",
                        "Agenda sin solapamientos ni condiciones de carrera",
                        "Los horarios de cada veterinario se protegen en base de datos mediante rangos temporales y actualizaciones condicionales compare-and-set con respuestas determinísticas.",
                        "Restricción EXCLUDE USING gist + control de concurrencia HTTP 409"),
                new BenefitItem(
                        "04 · TRAZABILIDAD",
                        "Evidencia append-only de operaciones sensibles",
                        "Los intentos de acceso, cambios de permisos, altas clínicas y transiciones de citas quedan registrados en tablas de auditoría e historial protegidas contra edición o borrado.",
                        "Registros atómicos en audit_entries, authentication_events y appointment_status_history"));
    }

    public static List<ImplementedModuleItem> implementedModules() {
        return List.of(
                new ImplementedModuleItem(
                        "MÓDULO 01",
                        "Autenticación multi-tenant",
                        "HU-005 · HU-006",
                        "Implementado",
                        "Inicio de sesión aislado por clínica (clinicSlug + correo + contraseña), emisión de JWT firmado con autoridades reales y verificación explícita de sesión antes de habilitar la interfaz.",
                        List.of(
                                "Resolución estricta de identidad mediante PetCarePrincipal y TenantContext.",
                                "Registro append-only de intentos de autenticación sin exponer contraseñas ni tokens.",
                                "Manejo accesible de expiración de sesión y cierre de sesión local seguro.")),
                new ImplementedModuleItem(
                        "MÓDULO 02",
                        "Usuarios, roles y permisos",
                        "HU-007 · HU-008",
                        "Implementado",
                        "Administración tenant-aware del personal de la clínica, asignación de sucursales operativas y configuración controlada de permisos sobre los cuatro roles del MVP.",
                        List.of(
                                "Roles por clínica: ADMINISTRADOR, VETERINARIO, RECEPCIONISTA y ALMACEN.",
                                "Protección invariante del último administrador activo y de las autoridades mínimas de gestión.",
                                "Auditoría transaccional en cada alta, cambio de estado o actualización de permisos.")),
                new ImplementedModuleItem(
                        "MÓDULO 03",
                        "Propietarios",
                        "HU-009 · HU-010 · HU-014",
                        "Implementado",
                        "Padrón de responsables de mascotas con búsqueda por nombre, documento o teléfono, registro validado por clínica y edición trazable de datos de contacto.",
                        List.of(
                                "Unicidad de documento y validaciones de formato dentro del ámbito de cada clínica.",
                                "Búsqueda rápida orientada al flujo de recepción antes de programar una atención.",
                                "Actualización controlada con permiso OWNER_UPDATE y registro en audit_entries.")),
                new ImplementedModuleItem(
                        "MÓDULO 04",
                        "Pacientes y perfil clínico básico",
                        "HU-011 · HU-012 · HU-013 · HU-014",
                        "Implementado",
                        "Registro de pacientes asociados a un propietario del mismo tenant, apertura automática del expediente clínico inicial y vista de perfil con alertas médicas visibles.",
                        List.of(
                                "Búsqueda paginada por paciente, propietario o especie con ordenamiento controlado.",
                                "Destacado visual permanente de alergias y notas de alerta en el perfil del paciente.",
                                "Edición de datos del paciente con permiso PATIENT_UPDATE y acceso directo para agendar cita.")),
                new ImplementedModuleItem(
                        "MÓDULO 05",
                        "Agenda, creación, estados, reprogramación y cancelación de citas",
                        "HU-015 · HU-016 · HU-017 · HU-018",
                        "Implementado",
                        "Gestión integral de citas por fecha, sucursal y veterinario asignado, desde la programación inicial en estado PROGRAMADA hasta la confirmación, espera, reprogramación o cancelación.",
                        List.of(
                                "Conversión horaria precisa según la zona IANA configurada en cada sucursal.",
                                "Transiciones controladas (PROGRAMADA, CONFIRMADA, EN_ESPERA, CANCELADA) con historial append-only.",
                                "Reprogramación y cancelación con motivo obligatorio protegidas por compare-and-set y eventos AFTER_COMMIT.")));
    }

    public static List<UpcomingModuleItem> upcomingModules() {
        return List.of(
                new UpcomingModuleItem(
                        "ROADMAP 01",
                        "Consultas clínicas completas",
                        "Próximamente",
                        "Sprint 2 — Flujo médico",
                        "Apertura y registro del acto médico veterinario desde la cita en espera, incluyendo anamnesis, evaluación física, peso, temperatura y observaciones clínicas."),
                new UpcomingModuleItem(
                        "ROADMAP 02",
                        "Diagnósticos y prescripciones",
                        "Próximamente",
                        "Sprint 2 — Cierre clínico",
                        "Asociación de diagnósticos clínicos a la consulta e indicación estructurada de medicamentos, dosis, frecuencia y duración vinculada al expediente del paciente."),
                new UpcomingModuleItem(
                        "ROADMAP 03",
                        "Inventario, lotes y alertas",
                        "Próximamente",
                        "Sprint 3 — Operación de almacén",
                        "Catálogo de productos e insumos por sucursal, control de lotes con fecha de vencimiento (FEFO), kardex inmutable de movimientos y alertas por stock mínimo o caducidad."),
                new UpcomingModuleItem(
                        "ROADMAP 04",
                        "Reportes avanzados",
                        "Próximamente",
                        "Sprint 3 — Indicadores operativos",
                        "Consultas operativas e indicadores por clínica y sucursal sobre actividad de agenda, atenciones realizadas, movimientos de stock y trazabilidad de auditoría."));
    }

    public static List<StackItem> technologyStack() {
        return List.of(
                new StackItem(
                        "Lenguaje y Runtime",
                        "Eclipse Temurin Java 17 LTS",
                        "Tipado estático, records inmutables y base de ejecución para el monolito modular."),
                new StackItem(
                        "Backend y Vista Pública",
                        "Spring Boot 3.5.16 · Spring Web MVC · Thymeleaf",
                        "API REST JSON bajo /api/v1/** y renderizado server-side con plantillas y fragmentos reutilizables en /presentacion."),
                new StackItem(
                        "Seguridad y Multi-Tenancy",
                        "Spring Security · OAuth2 Resource Server (JWT) · RBAC",
                        "Sesiones stateless para la API, resolución de TenantContext y rutas públicas explícitamente acotadas."),
                new StackItem(
                        "Base de Datos y Migraciones",
                        "PostgreSQL 18 · Flyway (V1–V14)",
                        "Esquema relacional con aislamiento lógico por tenant_id, restricciones btree_gist y tablas append-only."),
                new StackItem(
                        "Interfaz y Sistema de Diseño",
                        "HTML5 · Bootstrap 5.3.3 · CSS Clínica Editorial",
                        "Diseño responsive accesible (WCAG 2.1 AA), tipografía editorial Newsreader + Source Sans 3 y cero dependencia de Node.js."),
                new StackItem(
                        "Infraestructura y Entorno",
                        "Docker Compose · Nginx · JUnit 5 + Testcontainers",
                        "Despliegue reproducible multicontenedor, proxy inverso unificado y validación automatizada de arquitectura e integración."));
    }
}
