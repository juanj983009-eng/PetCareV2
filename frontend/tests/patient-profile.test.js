import { ApiRequestError } from "../assets/js/api-client.js";
import { createPatientsController } from "../assets/js/patients-controller.js";

const PATIENT_ID = "aaaaaaaa-1111-4111-8111-111111111111";
const OWNER_ID = "bbbbbbbb-2222-4222-8222-222222222222";
const RECORD_ID = "cccccccc-3333-4333-8333-333333333333";
const tests = [];

test("sin PATIENT_READ oculta la acción Ver perfil y bloquea openPatientProfile con estado forbidden", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      return {};
    }
  };
  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["DASHBOARD_VIEW"] });

  await controller.openPatientProfile(PATIENT_ID);
  assert(!document.querySelector("#patient-profile-view").hidden, "Debe abrir #patient-profile-view");
  assert(!document.querySelector("#patient-profile-forbidden").hidden, "Debe mostrar #patient-profile-forbidden");
  assert(document.querySelector("#patient-profile-content").hidden, "El contenido del perfil debe estar oculto");
  equal(calls.length, 0);
});

test("Ver perfil usa el id de GET /api/v1/patients, renderiza datos con textContent, banner condicional de alertas y regresa a la búsqueda conservando filtros y página", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      if (url.startsWith("/api/v1/patients?")) {
        return {
          content: [
            {
              id: PATIENT_ID,
              name: "Luna <script>window.__xss=1</script>",
              species: "CANINO",
              breed: "Mestizo",
              sex: "HEMBRA",
              microchip: "985112000123456",
              status: "ACTIVO",
              ownerId: OWNER_ID,
              ownerFullName: "Valeria Quispe <b>Hostil</b>",
              ownerDocumentType: "DNI",
              ownerDocumentNumber: "44881234"
            }
          ],
          page: 1,
          size: 10,
          totalElements: 15,
          totalPages: 2,
          sortBy: "name",
          direction: "asc"
        };
      }
      if (url === `/api/v1/patients/${PATIENT_ID}`) {
        return {
          id: PATIENT_ID,
          name: "Luna <script>window.__xss=1</script>",
          species: "CANINO",
          breed: "Mestizo",
          sex: "HEMBRA",
          birthDate: "2023-05-14",
          color: "Blanco y café",
          microchip: "985112000123456",
          sterilized: true,
          status: "ACTIVO",
          createdAt: "2026-04-01T10:00:00Z",
          owner: {
            id: OWNER_ID,
            fullName: "Valeria Quispe <b>Hostil</b>",
            documentType: "DNI",
            documentNumber: "44881234",
            phone: "999111222",
            email: "valeria@petcare.pe"
          },
          medicalRecord: {
            id: RECORD_ID,
            allergies: "Alergia a penicilina <img src=x onerror=alert(1)>",
            medicalAlerts: "Paciente braquicéfalo",
            history: "Control inicial preventivo sin hallazgos agudos.",
            createdAt: "2026-04-01T10:00:00Z"
          }
        };
      }
      throw new Error(`Ruta no esperada: ${url}`);
    }
  };

  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["PATIENT_READ"] });
  document.querySelector("#patients-nav-button").click();

  document.querySelector("#patient-search-query").value = "Luna";
  document.querySelector("#patient-species-filter").value = "CANINO";
  document.querySelector("#patient-status-filter").value = "ACTIVO";

  document.querySelector("#patient-search-form").dispatchEvent(new SubmitEvent("submit", { cancelable: true }));
  await flushPromises();

  const actionButton = document.querySelector("#patients-table-body button[data-action='view-patient-profile']");
  assert(Boolean(actionButton), "El listado debe renderizar botón Ver perfil con data-action=view-patient-profile");
  equal(actionButton.dataset.patientId, PATIENT_ID);
  equal(actionButton.textContent.trim(), "Ver perfil");

  actionButton.click();
  await flushPromises();

  equal(calls.length, 2);
  equal(calls[1], `/api/v1/patients/${PATIENT_ID}`);
  assert(document.querySelector("#patients-view").hidden, "La vista de búsqueda debe ocultarse al abrir el perfil");
  assert(!document.querySelector("#patient-profile-view").hidden, "La vista #patient-profile-view debe mostrarse");
  assert(!document.querySelector("#patient-profile-content").hidden, "Debe mostrar #patient-profile-content");

  equal(document.querySelector("#patient-profile-title").textContent, "Luna <script>window.__xss=1</script>");
  equal(document.querySelectorAll("#patient-profile-view script").length, 0);
  assert(!document.querySelector("#patient-profile-medical-alerts-banner").hidden, "El banner de alertas debe mostrarse cuando existen alergias o alertas reales");
  assert(document.querySelector("#patient-profile-no-alerts").hidden, "El aviso sin alertas debe ocultarse cuando existen alertas");
  assert(document.querySelector("#patient-profile-allergies-value").textContent.includes("Alergia a penicilina <img src=x onerror=alert(1)>"));
  assert(document.querySelector("#patient-profile-alerts-value").textContent.includes("Paciente braquicéfalo"));
  assert(document.querySelector("#patient-profile-identification").textContent.includes("985112000123456"));
  assert(document.querySelector("#patient-profile-owner").textContent.includes("Valeria Quispe <b>Hostil</b>"));
  assert(document.querySelector("#patient-profile-owner").textContent.includes("valeria@petcare.pe"));
  assert(document.querySelector("#patient-profile-record-history").textContent.includes("Control inicial preventivo"));

  assert(document.querySelector("#patient-profile-new-consultation-button").hidden, "Nueva consulta debe permanecer oculto en Sprint 1");
  assert(document.querySelector("#patient-profile-new-consultation-button").disabled, "Nueva consulta debe permanecer deshabilitado en Sprint 1");
  assert(document.querySelector("#patient-profile-new-appointment-button").hidden, "Agendar cita debe permanecer oculto en Sprint 1");
  assert(document.querySelector("#patient-profile-new-appointment-button").disabled, "Agendar cita debe permanecer deshabilitado en Sprint 1");

  document.querySelector("#patient-profile-back-button").click();
  assert(document.querySelector("#patient-profile-view").hidden, "El perfil debe ocultarse al volver a resultados");
  assert(!document.querySelector("#patients-view").hidden, "Debe restaurar #patients-view");
  assert(!document.querySelector("#patients-content").hidden, "Debe conservar los resultados ya cargados");
  equal(document.querySelector("#patient-search-query").value, "Luna");
  equal(document.querySelector("#patient-species-filter").value, "CANINO");
  equal(document.querySelector("#patient-status-filter").value, "ACTIVO");
  assert(document.querySelector("#patients-page-status").textContent.includes("Página 2 de 2"), "Debe conservar la página activa");
  equal(calls.length, 2);
});

test("oculta banner de alertas cuando no existen alergias ni alertas médicas y soporta estados 404 y 500 con reintento", async () => {
  let attempt = 0;
  const client = {
    requestJson: async () => {
      attempt += 1;
      if (attempt === 1) {
        throw new ApiRequestError({
          kind: "HTTP",
          status: 404,
          code: "RESOURCE_NOT_FOUND",
          message: "No se encontró el paciente solicitado."
        });
      }
      if (attempt === 2) {
        throw new ApiRequestError({
          kind: "HTTP",
          status: 500,
          code: "INTERNAL_ERROR",
          message: "Error temporal al cargar perfil clínico."
        });
      }
      return {
        id: PATIENT_ID,
        name: "Milo",
        species: "FELINO",
        breed: null,
        sex: "MACHO",
        birthDate: null,
        color: null,
        microchip: null,
        sterilized: false,
        status: "ACTIVO",
        createdAt: "2026-04-02T10:00:00Z",
        owner: {
          id: OWNER_ID,
          fullName: "Carlos Rojas",
          documentType: "DNI",
          documentNumber: "70112233",
          phone: "988777666",
          email: null
        },
        medicalRecord: {
          id: RECORD_ID,
          allergies: null,
          medicalAlerts: null,
          history: null,
          createdAt: "2026-04-02T10:00:00Z"
        }
      };
    }
  };

  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["PATIENT_READ"] });

  await controller.openPatientProfile(PATIENT_ID);
  assert(!document.querySelector("#patient-profile-not-found").hidden, "Con 404 debe mostrar #patient-profile-not-found");
  assert(document.querySelector("#patient-profile-not-found-message").textContent.includes("No se encontró el paciente solicitado."));

  await controller.openPatientProfile(PATIENT_ID);
  assert(!document.querySelector("#patient-profile-error").hidden, "Con 500 debe mostrar #patient-profile-error");
  assert(document.querySelector("#patient-profile-error-message").textContent.includes("Error temporal"));

  document.querySelector("#retry-patient-profile").click();
  await flushPromises();

  assert(!document.querySelector("#patient-profile-content").hidden, "Tras reintentar con éxito debe mostrar #patient-profile-content");
  assert(document.querySelector("#patient-profile-medical-alerts-banner").hidden, "Sin alergias ni alertas médicas debe ocultar el banner de alertas");
  assert(!document.querySelector("#patient-profile-no-alerts").hidden, "Sin alergias ni alertas debe mostrar #patient-profile-no-alerts");
});

run();

function test(name, fn) {
  tests.push({ name, fn });
}

function assert(condition, message = "Assertion failed") {
  if (!condition) {
    throw new Error(message);
  }
}

function equal(actual, expected) {
  if (actual !== expected) {
    throw new Error(`Se esperaba "${expected}" y se recibió "${actual}"`);
  }
}

async function flushPromises() {
  await new Promise((resolve) => setTimeout(resolve, 15));
}

async function run() {
  const results = document.querySelector("#test-results");
  const summary = document.querySelector("#test-summary");
  let passed = 0;

  for (const current of tests) {
    const item = document.createElement("li");
    try {
      await current.fn();
      passed += 1;
      item.textContent = `OK — ${current.name}`;
    } catch (error) {
      item.textContent = `FALLO — ${current.name}: ${error.message}`;
    }
    results.append(item);
  }

  summary.textContent = `${passed}/${tests.length} pruebas aprobadas`;
  document.documentElement.dataset.testStatus = passed === tests.length ? "passed" : "failed";
}
