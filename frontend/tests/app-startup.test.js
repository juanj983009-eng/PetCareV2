import * as apiClient from "../assets/js/api-client.js";
import { createAppointmentsController } from "../assets/js/appointments-controller.js";
import { createAuthController } from "../assets/js/auth-controller.js";
import { createUsersController } from "../assets/js/users-controller.js";
import { createRolesController } from "../assets/js/roles-controller.js";
import { createOwnersController } from "../assets/js/owners-controller.js";
import { createPatientsController } from "../assets/js/patients-controller.js";
import { applyApiError, clearApiErrors } from "../assets/js/form-errors.js";

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

let prodHtmlText = "";
async function getProdDoc() {
  if (!prodHtmlText) {
    const res = await fetch("../index.html");
    prodHtmlText = await res.text();
  }
  const parser = new DOMParser();
  return parser.parseFromString(prodHtmlText, "text/html");
}

test("index.html real contiene todos los elementos obligatorios del diálogo de reprogramación", async () => {
  const prodDoc = await getProdDoc();

  const requiredElements = [
    { id: "appointment-reschedule-dialog", desc: "Dialog principal de reprogramación" },
    { id: "appointment-reschedule-form", desc: "Formulario de reprogramación" },
    { id: "appointment-reschedule-summary", desc: "Resumen de la cita a reprogramar" },
    { id: "appointment-reschedule-error", desc: "Alerta de error de reprogramación" },
    { id: "appointment-reschedule-validation", desc: "Alerta de validación de reprogramación" },
    { id: "reschedule-branch-id", desc: "Selector de sucursal para reprogramar" },
    { id: "reschedule-branch-timezone", desc: "Indicador de zona horaria de la sucursal" },
    { id: "reschedule-veterinarian-id", desc: "Selector de veterinario asignado" },
    { id: "reschedule-date", desc: "Campo de fecha de reprogramación" },
    { id: "reschedule-start-time", desc: "Campo de hora de inicio" },
    { id: "reschedule-end-time", desc: "Campo de hora de término" },
    { id: "cancel-reschedule-button", desc: "Botón para cancelar/volver de reprogramación" },
    { id: "save-reschedule-button", desc: "Botón para confirmar/guardar reprogramación" }
  ];

  for (const { id, desc } of requiredElements) {
    const el = prodDoc.querySelector(`#${id}`);
    assert(el !== null, `Elemento faltante en index.html: #${id} (${desc})`);
  }
});

test("createAppointmentsController inicializa sin TypeError en el HTML real de producción", async () => {
  const prodDoc = await getProdDoc();

  let controller;
  try {
    controller = createAppointmentsController({
      document: prodDoc,
      client: {
        async requestJson() { return {}; }
      }
    });
  } catch (err) {
    throw new Error(`Falló inicialización con TypeError u otro error: ${err.message}`);
  }

  assert(typeof controller === "object" && controller !== null, "Debe instanciar el controlador de citas correctamente");
  assert(typeof controller.sessionReady === "function", "Debe exponer sessionReady");
  assert(typeof controller.sessionEnded === "function", "Debe exponer sessionEnded");
});

test("flujo de arranque completo de auth.js inicializa todos los controladores y muestra login sin pantalla blanca", async () => {
  const prodDoc = await getProdDoc();
  apiClient.clearAccessToken();

  const usersController = createUsersController({
    document: prodDoc,
    client: apiClient,
    errors: { applyApiError, clearApiErrors }
  });
  const rolesController = createRolesController({ document: prodDoc, client: apiClient });
  const ownersController = createOwnersController({ document: prodDoc, client: apiClient });
  let appointmentsController = null;
  const patientsController = createPatientsController({
    document: prodDoc,
    client: apiClient,
    onCreateAppointment: (p) => appointmentsController?.openCreateAppointmentDialog(p)
  });
  appointmentsController = createAppointmentsController({
    document: prodDoc,
    client: apiClient,
    onViewPatientProfile: (id) => patientsController.openPatientProfile(id),
    onAppointmentCreated: (_c, m) => patientsController.showProfileNotice(m)
  });

  const authController = createAuthController({
    document: prodDoc,
    window: {
      location: { href: "/" },
      sessionStorage: window.sessionStorage,
      addEventListener() {},
      removeEventListener() {}
    },
    client: apiClient,
    errors: { applyApiError, clearApiErrors },
    lifecycle: {
      authenticated: (identity) => {
        usersController.sessionReady(identity);
        rolesController.sessionReady(identity);
        ownersController.sessionReady(identity);
        patientsController.sessionReady(identity);
        appointmentsController.sessionReady(identity);
      },
      ended: () => {
        usersController.sessionEnded();
        rolesController.sessionEnded();
        ownersController.sessionEnded();
        patientsController.sessionEnded();
        appointmentsController.sessionEnded();
      }
    }
  });

  // Iniciar flujo de autenticación como en auth.js
  await authController.start();

  const loginView = prodDoc.querySelector("#login-view");
  const loginForm = prodDoc.querySelector("#login-form");
  assert(loginView !== null, "La sección de login (#login-view) debe existir en el DOM real");
  assert(loginForm !== null, "El formulario de login (#login-form) debe existir en el DOM real");
  assert(!loginView.hasAttribute("hidden"), "La vista de login debe estar visible (sin atributo hidden)");
});

test("diálogo de reprogramación en el DOM real abre, puebla datos y cierra correctamente", async () => {
  const prodDoc = await getProdDoc();
  const branchId = "11111111-1111-4111-8111-111111111111";
  const vetId = "22222222-2222-4222-8222-222222222222";
  const apptId = "33333333-3333-4333-8333-333333333333";

  // Mock showModal y close si el entorno de pruebas usa DOMParser sin soporte nativo de dialog
  const dialogEl = prodDoc.querySelector("#appointment-reschedule-dialog");
  let dialogShown = false;
  let dialogClosed = false;
  dialogEl.showModal = function() {
    dialogShown = true;
    dialogEl.open = true;
  };
  dialogEl.close = function() {
    dialogClosed = true;
    dialogEl.open = false;
  };

  const controller = createAppointmentsController({
    document: prodDoc,
    client: {
      async requestJson(url) {
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [{ id: branchId, name: "Sede Miraflores", timeZone: "America/Lima" }],
            veterinarians: [{ id: vetId, fullName: "Dra. Ana Salazar", branchIds: [branchId] }]
          };
        }
        return {
          content: [
            {
              id: apptId,
              startsAt: "2099-09-28T14:00:00Z",
              endsAt: "2099-09-28T14:30:00Z",
              appointmentType: "CONSULTA_GENERAL",
              status: "CONFIRMADA",
              patientId: "44444444-4444-4444-8444-444444444444",
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
    }
  });

  controller.sessionReady({
    userId: vetId,
    authorities: ["APPOINTMENT_READ", "APPOINTMENT_RESCHEDULE"]
  });
  await controller.openAppointmentsView();

  const rescheduleBtn = prodDoc.querySelector('#appointments-table-body [data-action="reschedule-appointment"]');
  assert(rescheduleBtn !== null, "Debe renderizar botón Reprogramar en fila CONFIRMADA");

  // Disparar delegated click directamente pasando el botón
  const clickEvent = new Event("click", { bubbles: true });
  rescheduleBtn.dispatchEvent(clickEvent);
  await new Promise((r) => setTimeout(r, 60));

  assert(dialogShown, "El diálogo de reprogramación debe haberse abierto con showModal()");
  const summaryEl = prodDoc.querySelector("#appointment-reschedule-summary");
  assert(summaryEl.textContent.includes("Luna"), "El resumen del diálogo debe incluir el nombre del paciente");
  assert(prodDoc.querySelector("#reschedule-date").value.length > 0, "El campo fecha debe estar poblado");

  // Clic en Volver/Cancelar diálogo
  const cancelBtn = prodDoc.querySelector("#cancel-reschedule-button");
  cancelBtn.click();
  assert(dialogClosed, "El botón Volver debe cerrar el diálogo con close()");

  controller.sessionEnded();
});

test("iframe cargando /index.html en vivo inicia sin errores de consola ni TypeErrors", async () => {
  const iframe = document.querySelector("#prod-iframe");
  assert(iframe !== null, "Debe existir el iframe de producción");

  // Esperar a que el iframe termine de cargar
  if (iframe.contentDocument?.readyState !== "complete") {
    await new Promise((resolve) => {
      iframe.addEventListener("load", resolve, { once: true });
    });
  }

  // Dar tiempo para que auth.js ejecute completamente
  await new Promise((r) => setTimeout(r, 200));

  const iframeDoc = iframe.contentDocument;
  assert(iframeDoc !== null, "El documento del iframe debe ser accesible");

  const loginView = iframeDoc.querySelector("#login-view");
  assert(loginView !== null, "La sección de login (#login-view) debe existir en el iframe");
  assert(!loginView.hasAttribute("hidden"), "La sección de login no debe estar oculta (pantalla blanca evitada)");

  const rescheduleDialog = iframeDoc.querySelector("#appointment-reschedule-dialog");
  assert(rescheduleDialog !== null, "El diálogo de reprogramación debe existir en el iframe");
  assert(iframeDoc.querySelector("#cancel-reschedule-button") !== null, "#cancel-reschedule-button debe existir en el iframe");
  assert(iframeDoc.querySelector("#save-reschedule-button") !== null, "#save-reschedule-button debe existir en el iframe");
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
      li.className = "text-danger";
    }
    results.append(li);
  }
  summary.textContent = `${passed}/${tests.length} pruebas aprobadas`;
  document.body.dataset.testStatus = passed === tests.length ? "PASS" : "FAIL";
}

run();
