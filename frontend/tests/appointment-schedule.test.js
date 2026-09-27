import { ApiRequestError } from "../assets/js/api-client.js";
import { createAppointmentsController } from "../assets/js/appointments-controller.js";

const summary = document.querySelector("#test-summary");
const results = document.querySelector("#test-results");
const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message || "Assertion failed");
  }
}

test("oculta la navegación de agenda sin APPOINTMENT_READ y mantiene Nueva cita deshabilitada", async () => {
  const calls = [];
  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url) => {
        calls.push(url);
        return {};
      }
    }
  });

  controller.sessionReady({ authorities: ["PATIENT_READ"] });
  assert(document.querySelector("#appointments-nav-button").hidden === true, "El botón de agenda debe ocultarse sin APPOINTMENT_READ");
  assert(document.querySelector("#new-appointment-view-button").hidden === true, "Nueva cita debe permanecer oculta en HU-015");
  assert(document.querySelector("#new-appointment-view-button").disabled === true, "Nueva cita debe permanecer deshabilitada en HU-015");

  await controller.openAppointmentsView();
  assert(document.querySelector("#appointments-forbidden").hidden === false, "Debe mostrar estado forbidden sin APPOINTMENT_READ");
  assert(calls.length === 0, "No debe consultar API sin APPOINTMENT_READ");
  controller.sessionEnded();
});

test("carga opciones y agenda cronológica en la zona horaria de la sucursal con badges accesibles", async () => {
  const calls = [];
  const openedProfiles = [];
  const branchId = "11111111-1111-4111-8111-111111111111";
  const vetId = "22222222-2222-4222-8222-222222222222";
  const patientId = "33333333-3333-4333-8333-333333333333";

  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url) => {
        calls.push(url);
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [{ id: branchId, name: "Sede Miraflores", timeZone: "America/Lima" }],
            veterinarians: [{ id: vetId, fullName: "Dra. Ana Salazar" }]
          };
        }
        return {
          content: [
            {
              id: "44444444-4444-4444-8444-444444444444",
              startsAt: "2026-09-27T14:00:00Z",
              endsAt: "2026-09-27T14:30:00Z",
              appointmentType: "CONSULTA_GENERAL",
              status: "CONFIRMADA",
              patientId,
              patientName: "Luna",
              branchId,
              branchName: "Sede Miraflores",
              veterinarianId: vetId,
              veterinarianFullName: "Dra. Ana Salazar"
            }
          ],
          page: 0,
          size: 10,
          totalElements: 1,
          totalPages: 1
        };
      }
    },
    onViewPatientProfile: (id) => openedProfiles.push(id)
  });

  controller.sessionReady({ authorities: ["APPOINTMENT_READ", "PATIENT_READ"] });
  assert(document.querySelector("#appointments-nav-button").hidden === false, "Debe mostrar navegación con APPOINTMENT_READ");
  assert(/^\d{4}-\d{2}-\d{2}$/.test(document.querySelector("#appointment-date-filter").value), "Debe inicializar fecha local YYYY-MM-DD");

  await controller.openAppointmentsView();

  assert(calls.some((u) => u === "/api/v1/appointments/options"), "Debe cargar opciones de sucursales y veterinarios");
  assert(calls.some((u) => u.startsWith("/api/v1/appointments?date=")), "Debe consultar agenda por fecha");
  assert(document.querySelector("#appointments-results").hidden === false, "Debe mostrar resultados");

  const rowText = document.querySelector("#appointments-table-body").textContent;
  assert(rowText.includes("09:00") && rowText.includes("09:30"), `Debe formatear 14:00Z como 09:00 en America/Lima. Actual: ${rowText}`);
  assert(rowText.includes("Luna"), "Debe mostrar nombre del paciente");
  assert(rowText.includes("Consulta general"), "Debe mostrar tipo de cita legible");
  assert(rowText.includes("Dra. Ana Salazar"), "Debe mostrar nombre del veterinario");
  assert(rowText.includes("Sede Miraflores"), "Debe mostrar sucursal");
  assert(rowText.includes("● Confirmada"), "Debe mostrar badge con icono y texto accesible");

  const cardText = document.querySelector("#appointments-cards").textContent;
  assert(cardText.includes("09:00") && cardText.includes("● Confirmada"), "Las tarjetas móviles deben mostrar horario y estado accesible");

  const viewBtn = document.querySelector("[data-action='view-appointment-patient']");
  assert(Boolean(viewBtn), "Debe mostrar botón Ver perfil cuando tiene PATIENT_READ");
  viewBtn.click();
  assert(openedProfiles[0] === patientId, "Debe delegar la apertura del perfil clínico con el patientId");
  controller.sessionEnded();
});

test("valida fecha inválida y maneja estados vacío y error con reintento", async () => {
  let shouldFail = true;
  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url) => {
        if (url === "/api/v1/appointments/options") {
          return { branches: [], veterinarians: [] };
        }
        if (shouldFail) {
          throw new ApiRequestError({ kind: "HTTP", status: 500, code: "INTERNAL_ERROR", message: "Fallo temporal de agenda" });
        }
        return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
      }
    }
  });

  controller.sessionReady({ authorities: ["APPOINTMENT_READ"] });
  await controller.openAppointmentsView();

  assert(document.querySelector("#appointments-error").hidden === false, "Debe mostrar error ante fallo 500");
  assert(document.querySelector("#appointments-error-message").textContent.includes("Fallo temporal"), "Debe mostrar mensaje de error");

  shouldFail = false;
  document.querySelector("#appointments-retry-button").click();
  await new Promise((r) => setTimeout(r, 25));
  assert(document.querySelector("#appointments-empty").hidden === false, "Tras reintentar sin citas debe mostrar estado vacío");

  const dateInput = document.querySelector("#appointment-date-filter");
  dateInput.value = "";
  document.querySelector("#appointment-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 15));
  assert(document.querySelector("#appointments-validation").hidden === false, "Debe mostrar alerta de validación si falta fecha");
  assert(dateInput.getAttribute("aria-invalid") === "true", "Debe marcar aria-invalid en el input de fecha");
  controller.sessionEnded();
});

async function run() {
  let passed = 0;
  for (const t of tests) {
    const li = document.createElement("li");
    try {
      await t.fn();
      passed += 1;
      li.textContent = `PASS — ${t.name}`;
      li.className = "text-success";
    } catch (error) {
      li.textContent = `FAIL — ${t.name}: ${error.message}`;
      li.className = "text-danger fw-semibold";
    }
    results.append(li);
  }
  summary.textContent = `${passed}/${tests.length} pruebas aprobadas`;
  document.body.dataset.testStatus = passed === tests.length ? "PASS" : "FAIL";
}

run();
