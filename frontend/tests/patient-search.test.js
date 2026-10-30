import { ApiRequestError } from "../assets/js/api-client.js";
import { createPatientsController } from "../assets/js/patients-controller.js";

const PATIENT_ID = "aaaaaaaa-1111-4111-8111-111111111111";
const OWNER_ID = "bbbbbbbb-2222-4222-8222-222222222222";
const tests = [];

test("visibilidad RBAC oculta navegación sin PATIENT_READ y muestra estado sin permiso si se invoca la vista", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      return {};
    }
  };
  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["DASHBOARD_VIEW"] });

  assert(document.querySelector("#patients-nav-button").hidden, "El botón de navegación debe estar oculto sin PATIENT_READ");
  assert(document.querySelector("#new-patient-button").hidden, "El botón de crear paciente debe estar oculto sin PATIENT_CREATE");

  controller.openSearchView();
  assert(!document.querySelector("#patients-forbidden").hidden, "Debe mostrarse el estado #patients-forbidden");
  assert(document.querySelector("#patients-initial").hidden, "El estado inicial debe permanecer oculto");
  equal(calls.length, 0);
});

test("validación previa rechaza búsqueda con 1 carácter sin llamar al backend", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      return {};
    }
  };
  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["PATIENT_READ"] });

  assert(!document.querySelector("#patients-nav-button").hidden, "El botón Pacientes debe mostrarse con PATIENT_READ");
  document.querySelector("#patients-nav-button").click();
  assert(!document.querySelector("#patients-view").hidden, "La vista #patients-view debe abrirse");
  assert(!document.querySelector("#patients-initial").hidden, "Debe iniciar en #patients-initial");

  const queryInput = document.querySelector("#patient-search-query");
  queryInput.value = "L";
  document.querySelector("#patient-search-form").dispatchEvent(new SubmitEvent("submit", { cancelable: true }));

  assert(!document.querySelector("#patients-validation").hidden, "Debe mostrar #patients-validation");
  equal(queryInput.getAttribute("aria-invalid"), "true");
  equal(calls.length, 0);
});

test("búsqueda con filtros, renderizado seguro con textContent, paginación y punto de integración HU-013", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      return {
        content: [
          {
            id: PATIENT_ID,
            name: "Luna <script>alert(1)</script>",
            species: "CANINO",
            breed: "Mestizo",
            sex: "HEMBRA",
            microchip: "985112000123456",
            status: "ACTIVO",
            ownerId: OWNER_ID,
            ownerFullName: "Valeria Quispe",
            ownerDocumentType: "DNI",
            ownerDocumentNumber: "44881234"
          }
        ],
        page: 0,
        size: 10,
        totalElements: 15,
        totalPages: 2,
        sortBy: "name",
        direction: "asc"
      };
    }
  };

  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["PATIENT_READ", "PATIENT_CREATE"] });
  document.querySelector("#patients-nav-button").click();

  document.querySelector("#patient-search-query").value = "Luna";
  document.querySelector("#patient-species-filter").value = "CANINO";
  document.querySelector("#patient-status-filter").value = "ACTIVO";
  document.querySelector("#patient-sort").value = "name";
  document.querySelector("#patient-direction").value = "asc";

  document.querySelector("#patient-search-form").dispatchEvent(new SubmitEvent("submit", { cancelable: true }));
  await flushPromises();

  equal(calls.length, 1);
  assert(calls[0].includes("size=6"), "Debe solicitar seis pacientes por página");
  assert(calls[0].includes("query=Luna"), "Debe enviar query=Luna");
  assert(calls[0].includes("species=CANINO"), "Debe enviar species=CANINO");
  assert(calls[0].includes("status=ACTIVO"), "Debe enviar status=ACTIVO");
  assert(calls[0].includes("sortBy=name"), "Debe enviar sortBy=name");

  assert(!document.querySelector("#patients-content").hidden, "Debe mostrar #patients-content");
  const rows = document.querySelectorAll("#patients-table-body tr");
  const cards = document.querySelectorAll("#patients-cards article");
  equal(rows.length, 1);
  equal(cards.length, 1);
  equal(rows[0].dataset.patientId, PATIENT_ID);
  assert(rows[0].textContent.includes("Luna <script>alert(1)</script>"), "Debe usar textContent escapando marcado");
  equal(rows[0].querySelectorAll("script").length, 0);
  assert(rows[0].textContent.includes("Valeria Quispe"), "Debe mostrar nombre del propietario");
  assert(rows[0].textContent.includes("DNI 44881234"), "Debe mostrar documento del propietario");
  assert(rows[0].textContent.includes("985112000123456"), "Debe mostrar microchip");

  const profileSlot = rows[0].querySelector("[data-patient-profile-slot='true']");
  assert(Boolean(profileSlot), "Debe incluir punto de integración para HU-013");
  equal(profileSlot.dataset.patientId, PATIENT_ID);

  assert(document.querySelector("#patients-previous-page").disabled, "Página 0 debe deshabilitar Anterior");
  assert(!document.querySelector("#patients-next-page").disabled, "Con 2 páginas debe habilitar Siguiente");

  document.querySelector("#patients-next-page").click();
  await flushPromises();
  equal(calls.length, 2);
  assert(calls[1].includes("page=1"), "Debe solicitar page=1");

  document.querySelector("#clear-patient-filters").click();
  assert(!document.querySelector("#patients-initial").hidden, "Limpiar filtros debe restablecer #patients-initial");
  equal(document.querySelector("#patient-search-query").value, "");
});

test("maneja estados sin resultados (empty) y error de API con reintento", async () => {
  let shouldFail = true;
  const client = {
    requestJson: async () => {
      if (shouldFail) {
        throw new ApiRequestError({
          kind: "HTTP",
          status: 500,
          code: "INTERNAL_ERROR",
          message: "Error temporal al consultar pacientes."
        });
      }
      return {
        content: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
        sortBy: "name",
        direction: "asc"
      };
    }
  };

  const controller = createPatientsController({ document, client });
  controller.sessionReady({ authorities: ["PATIENT_READ"] });
  document.querySelector("#patients-nav-button").click();
  document.querySelector("#patient-search-query").value = "Inexistente";

  document.querySelector("#patient-search-form").dispatchEvent(new SubmitEvent("submit", { cancelable: true }));
  await flushPromises();

  assert(!document.querySelector("#patients-error").hidden, "Debe mostrar estado #patients-error");
  assert(document.querySelector("#patients-error-message").textContent.includes("Error temporal"));

  shouldFail = false;
  document.querySelector("#retry-patients").click();
  await flushPromises();

  assert(!document.querySelector("#patients-empty").hidden, "Tras reintentar sin coincidencias debe mostrar #patients-empty");
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
