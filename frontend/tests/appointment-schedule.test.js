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

test("oculta la navegación de agenda sin APPOINTMENT_READ y mantiene Nueva cita deshabilitada sin APPOINTMENT_CREATE", async () => {
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
  assert(document.querySelector("#new-appointment-view-button").hidden === true, "Nueva cita debe ocultarse sin APPOINTMENT_CREATE");
  assert(document.querySelector("#new-appointment-view-button").disabled === true, "Nueva cita debe deshabilitarse sin APPOINTMENT_CREATE");

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
            veterinarians: [{ id: vetId, fullName: "Dra. Ana Salazar", branchIds: [branchId] }]
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
  assert(document.querySelector("#new-appointment-view-button").hidden === true, "Sin APPOINTMENT_CREATE (ej. VETERINARIO) no debe mostrar Nueva cita");
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

test("crea cita desde agenda y perfil filtrando veterinarios por sucursal, mostrando 409 sin cerrar el modal y refrescando tras 201", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const branchB = "55555555-5555-4555-8555-555555555555";
  const vetAna = "22222222-2222-4222-8222-222222222222";
  const vetPedro = "66666666-6666-4666-8666-666666666666";
  const patientLuna = "33333333-3333-4333-8333-333333333333";

  let simulateConflict = true;
  const postPayloads = [];
  const createdCallbacks = [];

  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url, options = {}) => {
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [
              { id: branchA, name: "Sede Miraflores", timeZone: "America/Lima" },
              { id: branchB, name: "Sede San Isidro", timeZone: "America/Lima" }
            ],
            veterinarians: [
              { id: vetAna, fullName: "Dra. Ana Salazar", branchIds: [branchA] },
              { id: vetPedro, fullName: "Dr. Pedro Díaz", branchIds: [branchB] }
            ]
          };
        }
        if (url.startsWith("/api/v1/patients?query=")) {
          return {
            content: [
              { id: patientLuna, name: "Luna", ownerFullName: "Lucia Paredes", status: "ACTIVO" },
              { id: "99999999-9999-4999-8999-999999999999", name: "Inactivo", ownerFullName: "Lucia", status: "INACTIVO" }
            ]
          };
        }
        if (url === "/api/v1/appointments" && options.method === "POST") {
          postPayloads.push(options.body);
          if (simulateConflict) {
            throw new ApiRequestError({
              kind: "HTTP",
              status: 409,
              code: "APPOINTMENT_TIME_CONFLICT",
              message: "El veterinario seleccionado ya tiene una cita activa que se solapa con ese horario."
            });
          }
          return {
            id: "77777777-7777-4777-8777-777777777777",
            startsAt: "2026-09-28T14:00:00Z",
            endsAt: "2026-09-28T14:30:00Z",
            appointmentType: options.body.appointmentType,
            status: "PROGRAMADA",
            patientId: options.body.patientId,
            patientName: "Luna",
            branchId: options.body.branchId,
            branchName: "Sede Miraflores",
            veterinarianId: options.body.veterinarianId,
            veterinarianFullName: "Dra. Ana Salazar"
          };
        }
        return {
          content: [
            {
              id: "77777777-7777-4777-8777-777777777777",
              startsAt: "2026-09-28T14:00:00Z",
              endsAt: "2026-09-28T14:30:00Z",
              appointmentType: "VACUNACION",
              status: "PROGRAMADA",
              patientId: patientLuna,
              patientName: "Luna",
              branchId: branchA,
              branchName: "Sede Miraflores",
              veterinarianId: vetAna,
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
    onAppointmentCreated: (created, msg) => createdCallbacks.push({ created, msg })
  });

  controller.sessionReady({ authorities: ["APPOINTMENT_READ", "APPOINTMENT_CREATE", "PATIENT_READ"] });
  const newBtn = document.querySelector("#new-appointment-view-button");
  assert(newBtn.hidden === false && newBtn.disabled === false, "Con APPOINTMENT_CREATE debe mostrar y habilitar Nueva cita");

  await controller.openAppointmentsView();
  newBtn.click();
  await new Promise((r) => setTimeout(r, 20));

  const dialog = document.querySelector("#appointment-editor-dialog");
  assert(dialog.open === true, "Debe abrir el modal de creación de cita");

  // Buscar paciente activo
  const patientQuery = document.querySelector("#appointment-patient-query");
  patientQuery.value = "Luna";
  document.querySelector("#appointment-patient-search-btn").click();
  await new Promise((r) => setTimeout(r, 20));

  const patientSelect = document.querySelector("#appointment-patient-id");
  assert(patientSelect.value === patientLuna, "Debe seleccionar automáticamente el paciente activo encontrado");
  assert(patientSelect.options.length === 2, "Debe incluir solo pacientes ACTIVOS además del placeholder");

  // Seleccionar sucursal A y verificar filtrado de veterinarios y zona horaria
  const branchSelect = document.querySelector("#appointment-branch-id");
  branchSelect.value = branchA;
  branchSelect.dispatchEvent(new Event("change"));
  assert(
    document.querySelector("#appointment-branch-timezone").textContent.includes("America/Lima"),
    "Debe mostrar la zona horaria de la sucursal seleccionada"
  );

  const vetSelect = document.querySelector("#appointment-veterinarian-id");
  assert(vetSelect.value === vetAna, "Debe filtrar y seleccionar a Dra. Ana Salazar asignada a Sede Miraflores");

  // Completar datos de cita
  document.querySelector("#appointment-date").value = "2026-09-28";
  document.querySelector("#appointment-start-time").value = "09:00";
  document.querySelector("#appointment-end-time").value = "09:30";
  document.querySelector("#appointment-type").value = "VACUNACION";
  document.querySelector("#appointment-reason").value = "Refuerzo anual";

  // 1er intento: simula 409 Conflict -> no debe cerrar el modal ni perder datos
  document.querySelector("#appointment-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 25));

  assert(dialog.open === true, "Ante conflicto 409 el modal debe permanecer abierto");
  const errorAlert = document.querySelector("#appointment-form-error");
  assert(errorAlert.hidden === false && errorAlert.textContent.includes("solapa"), "Debe mostrar mensaje claro de solapamiento 409");
  assert(document.querySelector("#appointment-reason").value === "Refuerzo anual", "No debe perder los datos ingresados tras 409");

  // 2do intento: éxito 201 Created -> cierra modal, muestra confirmación y refresca agenda
  simulateConflict = false;
  document.querySelector("#appointment-start-time").value = "09:30";
  document.querySelector("#appointment-end-time").value = "10:00";
  document.querySelector("#appointment-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 30));

  assert(dialog.open === false, "Tras 201 Created debe cerrar el modal");
  assert(document.querySelector("#appointments-notice").hidden === false, "Debe mostrar confirmación visual en agenda");
  assert(postPayloads.length === 2, "Debe haber enviado 2 solicitudes POST");
  assert(postPayloads[1].startsAtLocal === "2026-09-28T09:30", "Debe enviar startsAtLocal ISO local");
  assert(postPayloads[1].endsAtLocal === "2026-09-28T10:00", "Debe enviar endsAtLocal ISO local");
  assert(createdCallbacks.length === 1, "Debe notificar onAppointmentCreated");

  // Apertura desde perfil de paciente con paciente preseleccionado
  await controller.openCreateAppointmentDialog({
    id: patientLuna,
    name: "Luna",
    ownerFullName: "Lucia Paredes"
  });
  assert(dialog.open === true, "Debe abrir modal desde perfil del paciente");
  assert(document.querySelector("#appointment-patient-search-group").hidden === true, "Con paciente preseleccionado debe ocultar el buscador libre");
  assert(patientSelect.value === patientLuna, "Debe fijar el paciente preseleccionado del perfil");
  dialog.close();
  controller.sessionEnded();
});

test("valida horario invertido en el formulario de nueva cita y bloquea doble envío concurrente", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const vetAna = "22222222-2222-4222-8222-222222222222";
  const patientLuna = "33333333-3333-4333-8333-333333333333";
  let postCount = 0;

  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url, options = {}) => {
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [{ id: branchA, name: "Sede Miraflores", timeZone: "America/Lima" }],
            veterinarians: [{ id: vetAna, fullName: "Dra. Ana Salazar", branchIds: [branchA] }]
          };
        }
        if (url === "/api/v1/appointments" && options.method === "POST") {
          postCount += 1;
          await new Promise((r) => setTimeout(r, 40));
          return {
            id: "88888888-8888-4888-8888-888888888888",
            startsAt: "2026-09-28T15:00:00Z",
            endsAt: "2026-09-28T15:30:00Z",
            appointmentType: "CONTROL",
            status: "PROGRAMADA",
            patientId: patientLuna,
            patientName: "Luna",
            branchId: branchA,
            branchName: "Sede Miraflores",
            veterinarianId: vetAna,
            veterinarianFullName: "Dra. Ana Salazar"
          };
        }
        return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
      }
    }
  });

  controller.sessionReady({ authorities: ["APPOINTMENT_READ", "APPOINTMENT_CREATE"] });
  await controller.openCreateAppointmentDialog({ id: patientLuna, name: "Luna", ownerFullName: "Lucia Paredes" });

  document.querySelector("#appointment-date").value = "2026-09-28";
  document.querySelector("#appointment-start-time").value = "11:00";
  document.querySelector("#appointment-end-time").value = "10:30";

  const form = document.querySelector("#appointment-editor-form");
  form.dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 15));

  assert(postCount === 0, "No debe enviar POST cuando hora de término es anterior o igual al inicio");
  assert(document.querySelector("#appointment-end-time").getAttribute("aria-invalid") === "true", "Debe marcar aria-invalid en hora de término");

  // Corregir horario y disparar dos submit consecutivos
  document.querySelector("#appointment-end-time").value = "11:30";
  form.dispatchEvent(new Event("submit", { cancelable: true }));
  form.dispatchEvent(new Event("submit", { cancelable: true }));
  assert(document.querySelector("#save-appointment-button").disabled === true, "Debe deshabilitar el botón mientras guarda");
  await new Promise((r) => setTimeout(r, 60));

  assert(postCount === 1, `Debe enviar únicamente 1 solicitud POST ante doble submit (enviados: ${postCount})`);
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

test("HU-017: oculta acciones sin APPOINTMENT_STATUS_UPDATE y muestra botones según estado con permiso", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const vetAna = "22222222-2222-4222-8222-222222222222";
  const apptProgramada = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const apptConfirmada = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const apptEnEspera = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

  let items = [
    {
      id: apptProgramada,
      startsAt: "2026-09-28T14:00:00Z",
      endsAt: "2026-09-28T14:30:00Z",
      appointmentType: "CONSULTA_GENERAL",
      status: "PROGRAMADA",
      patientId: "33333333-3333-4333-8333-333333333333",
      patientName: "Luna",
      branchId: branchA,
      branchName: "Sede Miraflores",
      veterinarianId: vetAna,
      veterinarianFullName: "Dra. Ana Salazar"
    },
    {
      id: apptConfirmada,
      startsAt: "2026-09-28T15:00:00Z",
      endsAt: "2026-09-28T15:30:00Z",
      appointmentType: "CONTROL",
      status: "CONFIRMADA",
      patientId: "44444444-4444-4444-8444-444444444444",
      patientName: "Max",
      branchId: branchA,
      branchName: "Sede Miraflores",
      veterinarianId: vetAna,
      veterinarianFullName: "Dra. Ana Salazar"
    },
    {
      id: apptEnEspera,
      startsAt: "2026-09-28T16:00:00Z",
      endsAt: "2026-09-28T16:30:00Z",
      appointmentType: "VACUNACION",
      status: "EN_ESPERA",
      patientId: "55555555-5555-4555-8555-555555555555",
      patientName: "Toby",
      branchId: branchA,
      branchName: "Sede Miraflores",
      veterinarianId: vetAna,
      veterinarianFullName: "Dra. Ana Salazar"
    }
  ];

  const patchCalls = [];
  const searchUrls = [];
  let failNextPatch = false;

  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url, options = {}) => {
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [{ id: branchA, name: "Sede Miraflores", timeZone: "America/Lima" }],
            veterinarians: [{ id: vetAna, fullName: "Dra. Ana Salazar", branchIds: [branchA] }]
          };
        }
        if (options.method === "PATCH") {
          const payload = typeof options.body === "string" ? JSON.parse(options.body) : options.body;
          patchCalls.push({ url, body: payload });
          await new Promise((r) => setTimeout(r, 35));
          if (failNextPatch) {
            failNextPatch = false;
            throw new ApiRequestError({
              kind: "HTTP",
              status: 409,
              code: "INVALID_APPOINTMENT_STATUS_TRANSITION",
              message: "No se puede volver a CONFIRMADA desde EN_ESPERA.",
              correlationId: "corr-status-409"
            });
          }
          const targetId = url.split("/")[4];
          items = items.map((item) =>
            item.id === targetId ? { ...item, status: payload.status } : item
          );
          return items.find((item) => item.id === targetId);
        }
        searchUrls.push(url);
        return {
          content: items,
          page: 0,
          size: 10,
          totalElements: items.length,
          totalPages: 1
        };
      }
    }
  });

  // 1. Veterinario sin APPOINTMENT_STATUS_UPDATE: no ve botones de cambio de estado
  controller.sessionReady({ authorities: ["APPOINTMENT_READ"] });
  await controller.openAppointmentsView();

  const tableButtonsWithoutPerm = document.querySelectorAll('#appointments-table-body [data-action="change-appointment-status"]');
  assert(tableButtonsWithoutPerm.length === 0, "Sin APPOINTMENT_STATUS_UPDATE no debe renderizar botones de estado");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("Programadas: 1"), "Debe mostrar indicador Programadas: 1");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("Confirmadas: 1"), "Debe mostrar indicador Confirmadas: 1");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("En espera: 1"), "Debe mostrar indicador En espera: 1");

  // 2. Recepcionista con APPOINTMENT_STATUS_UPDATE: ve acciones según estado
  controller.sessionReady({ authorities: ["APPOINTMENT_READ", "APPOINTMENT_STATUS_UPDATE"] });
  await controller.openAppointmentsView();
  document.querySelector("#appointment-date-filter").value = "2026-09-28";
  document.querySelector("#appointment-branch-filter").value = branchA;
  document.querySelector("#appointment-veterinarian-filter").value = vetAna;
  document.querySelector("#appointment-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 25));

  const rows = document.querySelectorAll("#appointments-table-body tr");
  const rowProgramadaActions = rows[0].querySelectorAll('[data-action="change-appointment-status"]');
  const rowConfirmadaActions = rows[1].querySelectorAll('[data-action="change-appointment-status"]');
  const rowEnEsperaActions = rows[2].querySelectorAll('[data-action="change-appointment-status"]');

  assert(rowProgramadaActions.length === 2, "PROGRAMADA debe mostrar 2 acciones (Confirmar y Registrar llegada)");
  assert(rowConfirmadaActions.length === 1, "CONFIRMADA debe mostrar solo 1 acción (Registrar llegada)");
  assert(rowConfirmadaActions[0].dataset.targetStatus === "EN_ESPERA", "Acción de CONFIRMADA debe apuntar a EN_ESPERA");
  assert(rowEnEsperaActions.length === 0, "EN_ESPERA no debe mostrar acciones adicionales en este corte");

  // 3. Confirmar cita PROGRAMADA con doble clic bloqueado y preservando filtros
  const confirmButton = rows[0].querySelector('[data-target-status="CONFIRMADA"]');
  confirmButton.click();
  confirmButton.click();
  assert(confirmButton.disabled === true, "Debe deshabilitar botón mientras procesa transición");
  await new Promise((r) => setTimeout(r, 60));

  assert(patchCalls.length === 1, `Doble clic debe enviar solo 1 PATCH (enviados: ${patchCalls.length})`);
  assert(patchCalls[0].url === `/api/v1/appointments/${apptProgramada}/status`, "Debe invocar PATCH /api/v1/appointments/{id}/status");
  assert(patchCalls[0].body.status === "CONFIRMADA", "Debe enviar status CONFIRMADA");
  assert(document.querySelector("#appointments-notice").hidden === false, "Debe mostrar confirmación visual tras actualizar estado");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("Programadas: 0"), "Tras confirmar debe actualizar Programadas: 0");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("Confirmadas: 2"), "Tras confirmar debe actualizar Confirmadas: 2");
  const lastSearchUrl = searchUrls[searchUrls.length - 1];
  assert(lastSearchUrl.includes(`branchId=${branchA}`) && lastSearchUrl.includes(`veterinarianId=${vetAna}`), "Debe preservar filtros activos al refrescar la agenda");

  // 4. Error 409 con correlationId al fallar transición
  failNextPatch = true;
  const updatedRows = document.querySelectorAll("#appointments-table-body tr");
  const checkInBtn = updatedRows[0].querySelector('[data-target-status="EN_ESPERA"]');
  checkInBtn.click();
  await new Promise((r) => setTimeout(r, 60));

  const statusErrorEl = document.querySelector("#appointments-status-error");
  assert(statusErrorEl.hidden === false, "Ante 409 debe mostrar alerta de error en agenda");
  assert(statusErrorEl.textContent.includes("corr-status-409"), "Alerta 409 debe incluir correlationId");

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
