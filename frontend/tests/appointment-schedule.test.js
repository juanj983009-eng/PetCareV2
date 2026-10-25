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
      startsAt: "2099-09-28T14:00:00Z",
      endsAt: "2099-09-28T14:30:00Z",
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
      startsAt: "2099-09-28T15:00:00Z",
      endsAt: "2099-09-28T15:30:00Z",
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
      startsAt: "2099-09-28T16:00:00Z",
      endsAt: "2099-09-28T16:30:00Z",
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
  document.querySelector("#appointment-date-filter").value = "2099-09-28";
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

test("HU-019: marca inasistencia solo en citas vencidas sin atención y actualiza la agenda", async () => {
  const branchId = "11111111-1111-4111-8111-111111111111";
  const vetId = "22222222-2222-4222-8222-222222222222";
  const base = {
    appointmentType: "CONSULTA_GENERAL",
    patientId: "33333333-3333-4333-8333-333333333333",
    patientName: "Luna",
    branchId,
    branchName: "Sede Miraflores",
    veterinarianId: vetId,
    veterinarianFullName: "Dra. Ana Salazar"
  };
  let items = [
    { ...base, id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", startsAt: "2020-01-01T14:00:00Z", endsAt: "2020-01-01T14:30:00Z", status: "PROGRAMADA" },
    { ...base, id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", startsAt: "2020-01-01T15:00:00Z", endsAt: "2020-01-01T15:30:00Z", status: "CONFIRMADA" },
    { ...base, id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", startsAt: "2100-01-01T14:00:00Z", endsAt: "2100-01-01T14:30:00Z", status: "PROGRAMADA" },
    { ...base, id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd", startsAt: "2020-01-01T16:00:00Z", endsAt: "2020-01-01T16:30:00Z", status: "EN_ESPERA" }
  ];
  const patchCalls = [];
  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url, options = {}) => {
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [{ id: branchId, name: "Sede Miraflores", timeZone: "America/Lima" }],
            veterinarians: [{ id: vetId, fullName: "Dra. Ana Salazar", branchIds: [branchId] }]
          };
        }
        if (options.method === "PATCH") {
          patchCalls.push({ url, body: options.body });
          await new Promise((resolve) => setTimeout(resolve, 25));
          const targetId = url.split("/")[4];
          items = items.map((item) => item.id === targetId ? { ...item, status: options.body.status } : item);
          return items.find((item) => item.id === targetId);
        }
        const parsedUrl = new URL(url, "http://localhost");
        const requestedPage = Number(parsedUrl.searchParams.get("page") ?? "0");
        const branchParam = parsedUrl.searchParams.get("branchId");
        const filteredItems = branchParam ? items.filter((item) => item.branchId === branchParam) : items;
        const pageContent = requestedPage === 0 ? filteredItems.slice(0, 2) : filteredItems.slice(2, 4);
        return {
          content: pageContent,
          page: requestedPage,
          size: 2,
          totalElements: filteredItems.length,
          totalPages: Math.ceil(filteredItems.length / 2),
          scheduledCount: filteredItems.filter((item) => item.status === "PROGRAMADA").length,
          confirmedCount: filteredItems.filter((item) => item.status === "CONFIRMADA").length,
          waitingCount: filteredItems.filter((item) => item.status === "EN_ESPERA").length,
          noShowCount: filteredItems.filter((item) => item.status === "NO_ASISTIO").length
        };
      }
    }
  });

  controller.sessionReady({ authorities: ["APPOINTMENT_READ"] });
  await controller.openAppointmentsView();
  assert(document.querySelectorAll('[data-target-status="NO_ASISTIO"]').length === 0,
    "Sin APPOINTMENT_STATUS_UPDATE no debe ofrecer inasistencia");

  controller.sessionReady({ authorities: ["APPOINTMENT_READ", "APPOINTMENT_STATUS_UPDATE"] });
  await controller.openAppointmentsView();
  const indicatorsBefore = document.querySelector("#appointments-status-indicators").textContent;
  assert(indicatorsBefore.includes("Programadas: 2"),
    "Indicadores en página 1 deben reflejar el total global de Programadas (2) aunque la página muestre solo 1");
  assert(indicatorsBefore.includes("Confirmadas: 1"),
    "Indicadores en página 1 deben reflejar el total global de Confirmadas (1)");
  assert(indicatorsBefore.includes("En espera: 1"),
    "Indicadores en página 1 deben reflejar el total global de En espera (1)");
  assert(indicatorsBefore.includes("No asistieron: 0"),
    "Indicadores en página 1 deben reflejar el total global de No asistieron (0)");

  // Navegar a página 2 conserva exactamente los mismos indicadores globales del filtro
  document.querySelector("#appointments-next-page").click();
  await new Promise((resolve) => setTimeout(resolve, 20));
  const indicatorsPage2 = document.querySelector("#appointments-status-indicators").textContent;
  assert(indicatorsPage2 === indicatorsBefore,
    "Los indicadores de estado deben mantenerse idénticos entre páginas para los mismos filtros");

  const rowsPage2 = document.querySelectorAll("#appointments-table-body tr");
  assert(rowsPage2[0].querySelector('[data-target-status="NO_ASISTIO"]') === null,
    "No debe ofrecer inasistencia antes de finalizar el horario");
  assert(rowsPage2[1].querySelector('[data-target-status="NO_ASISTIO"]') === null,
    "No debe ofrecer inasistencia si el paciente ya llegó");

  // Volver a página 1 para probar citas vencidas
  document.querySelector("#appointments-previous-page").click();
  await new Promise((resolve) => setTimeout(resolve, 20));
  const rows = document.querySelectorAll("#appointments-table-body tr");
  assert(rows[0].querySelector('[data-target-status="NO_ASISTIO"]') !== null,
    "Debe ofrecer inasistencia para PROGRAMADA vencida");
  assert(rows[1].querySelector('[data-target-status="NO_ASISTIO"]') !== null,
    "Debe ofrecer inasistencia para CONFIRMADA vencida");
  assert(document.querySelectorAll('#appointments-cards [data-target-status="NO_ASISTIO"]').length === 2,
    "La acción también debe estar disponible en tarjetas móviles");

  const noShowButton = rows[0].querySelector('[data-target-status="NO_ASISTIO"]');
  noShowButton.click();
  noShowButton.click();
  await new Promise((resolve) => setTimeout(resolve, 120));
  assert(patchCalls.length === 1, "El doble clic no debe enviar dos cambios de estado");
  assert(patchCalls[0].url === `/api/v1/appointments/${items[0].id}/status`, "Debe usar el endpoint existente");
  assert(patchCalls[0].body.status === "NO_ASISTIO", "Debe enviar el estado NO_ASISTIO");
  assert(document.querySelector("#appointments-notice").textContent.toLowerCase().includes("inasistencia"),
    "Debe confirmar la inasistencia al usuario");
  const indicatorsAfter = document.querySelector("#appointments-status-indicators").textContent;
  assert(indicatorsAfter.includes("Programadas: 1"),
    "El resumen global debe descontar la cita reprogramada a inasistencia (Programadas: 1)");
  assert(indicatorsAfter.includes("No asistieron: 1"),
    "El resumen global debe mostrar el conteo de inasistencias (No asistieron: 1)");
  assert(document.querySelector("#appointments-table-body tr").textContent.includes("No asistió"),
    "La agenda debe mostrar el nuevo estado");
  controller.sessionEnded();
});

test("HU-018: reprograma y cancela citas según permisos/estado, maneja 409 con correlationId y bloquea doble envío", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const branchB = "99999999-9999-4999-8999-999999999999";
  const vetAna = "22222222-2222-4222-8222-222222222222";
  const vetCarlos = "88888888-8888-4888-8888-888888888888";
  const apptProgramada = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const apptConfirmada = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const apptEnEspera = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const apptFinalizada = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

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
    },
    {
      id: apptFinalizada,
      startsAt: "2026-09-28T17:00:00Z",
      endsAt: "2026-09-28T17:30:00Z",
      appointmentType: "CIRUGIA",
      status: "FINALIZADA",
      patientId: "66666666-6666-4666-8666-666666666666",
      patientName: "Rocky",
      branchId: branchA,
      branchName: "Sede Miraflores",
      veterinarianId: vetAna,
      veterinarianFullName: "Dra. Ana Salazar"
    }
  ];

  const putCalls = [];
  const searchUrls = [];
  let failNextReschedule = false;

  const controller = createAppointmentsController({
    document,
    client: {
      requestJson: async (url, options = {}) => {
        if (url === "/api/v1/appointments/options") {
          return {
            branches: [
              { id: branchA, name: "Sede Miraflores", timeZone: "America/Lima" },
              { id: branchB, name: "Sede San Borja", timeZone: "America/Lima" }
            ],
            veterinarians: [
              { id: vetAna, fullName: "Dra. Ana Salazar", branchIds: [branchA] },
              { id: vetCarlos, fullName: "Dr. Carlos Vega", branchIds: [branchB] }
            ]
          };
        }
        if (options.method === "PUT") {
          const payload = typeof options.body === "string" ? JSON.parse(options.body) : options.body;
          putCalls.push({ url, body: payload });
          await new Promise((r) => setTimeout(r, 35));
          const targetId = url.split("/")[4];
          if (url.endsWith("/schedule")) {
            if (failNextReschedule) {
              failNextReschedule = false;
              throw new ApiRequestError({
                kind: "HTTP",
                status: 409,
                code: "APPOINTMENT_TIME_CONFLICT",
                message: "El veterinario ya cuenta con una cita activa en ese horario.",
                correlationId: "corr-resched-409"
              });
            }
            items = items.map((item) =>
              item.id === targetId
                ? {
                    ...item,
                    branchId: payload.branchId,
                    branchName: payload.branchId === branchB ? "Sede San Borja" : "Sede Miraflores",
                    veterinarianId: payload.veterinarianId,
                    veterinarianFullName: payload.veterinarianId === vetCarlos ? "Dr. Carlos Vega" : "Dra. Ana Salazar",
                    status: "PROGRAMADA"
                  }
                : item
            );
            return items.find((item) => item.id === targetId);
          }
          if (url.endsWith("/cancellation")) {
            items = items.map((item) =>
              item.id === targetId ? { ...item, status: "CANCELADA" } : item
            );
            return items.find((item) => item.id === targetId);
          }
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

  // 1. Sin permisos de reprogramar/cancelar: no muestra botones
  controller.sessionReady({ authorities: ["APPOINTMENT_READ"] });
  await controller.openAppointmentsView();
  assert(document.querySelectorAll('[data-action="reschedule-appointment"]').length === 0, "Sin APPOINTMENT_RESCHEDULE no debe mostrar Reprogramar");
  assert(document.querySelectorAll('[data-action="cancel-appointment"]').length === 0, "Sin APPOINTMENT_CANCEL no debe mostrar Cancelar");

  // 2. Con permisos: Reprogramar solo en PROGRAMADA y CONFIRMADA; Cancelar en PROGRAMADA, CONFIRMADA y EN_ESPERA (no en FINALIZADA)
  controller.sessionReady({ authorities: ["APPOINTMENT_READ", "APPOINTMENT_RESCHEDULE", "APPOINTMENT_CANCEL"] });
  await controller.openAppointmentsView();
  document.querySelector("#appointment-date-filter").value = "2026-09-28";
  document.querySelector("#appointment-branch-filter").value = branchA;
  document.querySelector("#appointment-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 25));

  const rows = document.querySelectorAll("#appointments-table-body tr");
  assert(rows[0].querySelector('[data-action="reschedule-appointment"]') !== null, "PROGRAMADA debe mostrar Reprogramar");
  assert(rows[0].querySelector('[data-action="cancel-appointment"]') !== null, "PROGRAMADA debe mostrar Cancelar");
  assert(rows[1].querySelector('[data-action="reschedule-appointment"]') !== null, "CONFIRMADA debe mostrar Reprogramar");
  assert(rows[1].querySelector('[data-action="cancel-appointment"]') !== null, "CONFIRMADA debe mostrar Cancelar");
  assert(rows[2].querySelector('[data-action="reschedule-appointment"]') === null, "EN_ESPERA no debe mostrar Reprogramar");
  assert(rows[2].querySelector('[data-action="cancel-appointment"]') !== null, "EN_ESPERA debe mostrar Cancelar");
  assert(rows[3].querySelector('[data-action="reschedule-appointment"]') === null, "FINALIZADA no debe mostrar Reprogramar");
  assert(rows[3].querySelector('[data-action="cancel-appointment"]') === null, "FINALIZADA no debe mostrar Cancelar");

  // 3. Reprogramar cita CONFIRMADA: maneja 409 con correlationId y luego éxito (vuelve a PROGRAMADA y bloquea doble envío)
  rows[1].querySelector('[data-action="reschedule-appointment"]').click();
  await new Promise((r) => setTimeout(r, 15));

  document.querySelector("#reschedule-branch-id").value = branchB;
  document.querySelector("#reschedule-branch-id").dispatchEvent(new Event("change"));
  document.querySelector("#reschedule-veterinarian-id").value = vetCarlos;
  document.querySelector("#reschedule-date").value = "2026-09-28";
  document.querySelector("#reschedule-start-time").value = "11:00";
  document.querySelector("#reschedule-end-time").value = "11:45";

  failNextReschedule = true;
  const reschedForm = document.querySelector("#appointment-reschedule-form");
  reschedForm.dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 55));

  assert(document.querySelector("#appointment-reschedule-error").hidden === false, "Debe mostrar error 409 en diálogo de reprogramación");
  assert(document.querySelector("#appointment-reschedule-error").textContent.includes("corr-resched-409"), "Error 409 debe incluir correlationId");

  // Envío exitoso con doble submit bloqueado
  putCalls.length = 0;
  reschedForm.dispatchEvent(new Event("submit", { cancelable: true }));
  reschedForm.dispatchEvent(new Event("submit", { cancelable: true }));
  assert(document.querySelector("#save-reschedule-button").disabled === true, "Debe deshabilitar botón Guardar reprogramación mientras envía");
  await new Promise((r) => setTimeout(r, 60));

  assert(putCalls.length === 1, `Doble submit en reprogramar debe enviar 1 PUT (enviados: ${putCalls.length})`);
  assert(putCalls[0].url === `/api/v1/appointments/${apptConfirmada}/schedule`, "Debe llamar PUT /api/v1/appointments/{id}/schedule");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("Programadas: 2"), "Cita CONFIRMADA reprogramada debe volver a Programadas (2)");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("Confirmadas: 0"), "Confirmadas debe bajar a 0");

  // 4. Cancelar cita EN_ESPERA: valida motivo obligatorio (3..500) y luego cancela preservando filtros
  const updatedRows = document.querySelectorAll("#appointments-table-body tr");
  updatedRows[2].querySelector('[data-action="cancel-appointment"]').click();

  const cancelForm = document.querySelector("#appointment-cancel-form");
  const cancelReasonInput = document.querySelector("#appointment-cancel-reason");
  cancelReasonInput.value = "ab";
  cancelForm.dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 15));
  assert(document.querySelector("#appointment-cancel-validation").hidden === false, "Debe validar motivo menor a 3 caracteres");

  putCalls.length = 0;
  cancelReasonInput.value = "Propietario solicitó cancelar por emergencia familiar";
  cancelForm.dispatchEvent(new Event("submit", { cancelable: true }));
  cancelForm.dispatchEvent(new Event("submit", { cancelable: true }));
  await new Promise((r) => setTimeout(r, 60));

  assert(putCalls.length === 1, `Doble submit en cancelar debe enviar 1 PUT (enviados: ${putCalls.length})`);
  assert(putCalls[0].url === `/api/v1/appointments/${apptEnEspera}/cancellation`, "Debe llamar PUT /api/v1/appointments/{id}/cancellation");
  assert(putCalls[0].body.reason === "Propietario solicitó cancelar por emergencia familiar", "Debe enviar motivo de cancelación");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("En espera: 0"), "Tras cancelar EN_ESPERA debe actualizar En espera: 0");
  const lastSearchUrl = searchUrls[searchUrls.length - 1];
  assert(lastSearchUrl.includes(`branchId=${branchA}`), "Debe preservar filtro branchId al refrescar agenda");

  controller.sessionEnded();
});

test("HU-020: muestra Iniciar consulta solo en EN_ESPERA para el veterinario con CONSULTATION_CREATE, maneja errores, bloquea doble envío y actualiza agenda", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const vet1 = "22222222-2222-4222-8222-222222222222";
  const vet2 = "33333333-3333-4333-8333-333333333333";
  const apptWaitingOwn = "aa000001-0000-4000-8000-000000000001";
  const apptWaitingOtherVet = "aa000002-0000-4000-8000-000000000002";
  const apptConfirmed = "aa000003-0000-4000-8000-000000000003";

  let items = [
    {
      id: apptWaitingOwn,
      startsAt: "2026-09-28T14:00:00Z",
      endsAt: "2026-09-28T14:30:00Z",
      appointmentType: "CONSULTA_GENERAL",
      status: "EN_ESPERA",
      patientId: "55555555-5555-4555-8555-555555555555",
      patientName: "Thor",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet1,
      veterinarianFullName: "Dra. Elena Rojas"
    },
    {
      id: apptWaitingOtherVet,
      startsAt: "2026-09-28T14:30:00Z",
      endsAt: "2026-09-28T15:00:00Z",
      appointmentType: "CONTROL",
      status: "EN_ESPERA",
      patientId: "66666666-6666-4666-8666-666666666666",
      patientName: "Milo",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet2,
      veterinarianFullName: "Dr. Marco Silva"
    },
    {
      id: apptConfirmed,
      startsAt: "2026-09-28T15:00:00Z",
      endsAt: "2026-09-28T15:30:00Z",
      appointmentType: "VACUNACION",
      status: "CONFIRMADA",
      patientId: "77777777-7777-4777-8777-777777777777",
      patientName: "Luna",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet1,
      veterinarianFullName: "Dra. Elena Rojas"
    }
  ];

  const postCalls = [];
  let failOnce = true;

  const controller = createAppointmentsController({
    document,
    client: {
      async requestJson(url, options = {}) {
        if (options.method === "POST" && url.endsWith("/consultation")) {
          postCalls.push({ url });
          await new Promise((r) => setTimeout(r, 25));
          if (failOnce) {
            failOnce = false;
            throw new ApiRequestError({
              kind: "HTTP",
              status: 409,
              code: "INVALID_APPOINTMENT_STATUS_TRANSITION",
              message: "La cita cambió de estado concurrentemente.",
              correlationId: "corr-hu020-409"
            });
          }
          items = items.map((item) => item.id === apptWaitingOwn ? { ...item, status: "EN_ATENCION" } : item);
          return {
            id: "cc000001-0000-4000-8000-000000000001",
            appointmentId: apptWaitingOwn,
            appointmentStatus: "EN_ATENCION",
            patientId: "55555555-5555-4555-8555-555555555555",
            patientName: "Thor",
            medicalRecordId: "dd000001-0000-4000-8000-000000000001",
            branchId: branchA,
            branchName: "Sede Principal",
            veterinarianId: vet1,
            veterinarianFullName: "Dra. Elena Rojas",
            status: "BORRADOR",
            startedAt: "2026-09-28T14:05:00Z"
          };
        }
        if (url.startsWith("/api/v1/appointments/options")) {
          return {
            branches: [{ id: branchA, name: "Sede Principal", timeZone: "America/Lima" }],
            veterinarians: [
              { id: vet1, fullName: "Dra. Elena Rojas", branchIds: [branchA] },
              { id: vet2, fullName: "Dr. Marco Silva", branchIds: [branchA] }
            ]
          };
        }
        return {
          content: items,
          page: 0,
          size: 10,
          totalElements: items.length,
          totalPages: 1,
          scheduledCount: items.filter((item) => item.status === "PROGRAMADA").length,
          confirmedCount: items.filter((item) => item.status === "CONFIRMADA").length,
          waitingCount: items.filter((item) => item.status === "EN_ESPERA").length,
          noShowCount: items.filter((item) => item.status === "NO_ASISTIO").length
        };
      }
    }
  });

  // 1. Sin CONSULTATION_CREATE (ej. RECEPCIONISTA) no se muestra "Iniciar consulta"
  controller.sessionReady({
    userId: vet1,
    authorities: ["APPOINTMENT_READ", "APPOINTMENT_STATUS_UPDATE"]
  });
  await controller.openAppointmentsView();
  assert(document.querySelectorAll('[data-action="start-consultation"]').length === 0,
    "Sin CONSULTATION_CREATE no debe renderizar botón Iniciar consulta");

  // 2. Con CONSULTATION_CREATE (VETERINARIO vet1) solo aparece en EN_ESPERA asignada a vet1 (escritorio y móvil)
  controller.sessionReady({
    userId: vet1,
    authorities: ["APPOINTMENT_READ", "CONSULTATION_CREATE"]
  });
  await controller.openAppointmentsView();

  const rows = document.querySelectorAll("#appointments-table-body tr");
  const initialStartBtn = rows[0].querySelector('[data-action="start-consultation"]');
  assert(initialStartBtn !== null,
    "Fila EN_ESPERA asignada al veterinario autenticado debe mostrar Iniciar atención");
  assert(initialStartBtn.textContent.trim() === "Iniciar atención",
    "El botón debe mostrar exactamente el texto 'Iniciar atención'");
  assert(rows[1].querySelector('[data-action="start-consultation"]') === null,
    "Fila EN_ESPERA asignada a otro veterinario no debe mostrar Iniciar consulta");
  assert(rows[2].querySelector('[data-action="start-consultation"]') === null,
    "Fila CONFIRMADA no debe mostrar Iniciar consulta");

  const mobileStartButtons = document.querySelectorAll('#appointments-cards [data-action="start-consultation"]');
  assert(mobileStartButtons.length === 1,
    "Las tarjetas móviles deben mostrar exactamente 1 botón Iniciar consulta para la cita EN_ESPERA propia");

  // 3. Primer clic falla con 409: muestra error accesible con correlationId y restaura botón
  const startBtn = rows[0].querySelector('[data-action="start-consultation"]');
  startBtn.click();
  await new Promise((r) => setTimeout(r, 40));
  const statusErrorEl = document.querySelector("#appointments-status-error");
  assert(statusErrorEl.hidden === false, "Debe mostrar error accesible al fallar inicio de consulta");
  assert(statusErrorEl.textContent.includes("corr-hu020-409"), "Error debe incluir correlationId");

  // 4. Doble clic envía una sola solicitud POST, muestra confirmación accesible y pasa cita a EN_ATENCION
  postCalls.length = 0;
  const retryBtn = document.querySelector('#appointments-table-body [data-action="start-consultation"]');
  retryBtn.click();
  retryBtn.click();
  await new Promise((r) => setTimeout(r, 50));

  assert(postCalls.length === 1, `Doble clic debe enviar 1 POST (enviados: ${postCalls.length})`);
  assert(postCalls[0].url === `/api/v1/appointments/${apptWaitingOwn}/consultation`,
    "Debe invocar POST /api/v1/appointments/{id}/consultation");

  const noticeEl = document.querySelector("#appointments-notice");
  assert(noticeEl.hidden === false, "Debe mostrar confirmación accesible tras iniciar consulta");
  assert(noticeEl.textContent.includes("Thor") && noticeEl.textContent.includes("borrador"),
    "Confirmación debe indicar que la consulta en borrador fue iniciada para el paciente");
  assert(document.querySelector("#appointments-table-body").textContent.includes("En atención"),
    "La agenda debe actualizar el estado de la cita a En atención");
  assert(document.querySelector("#appointments-status-indicators").textContent.includes("En espera: 1"),
    "El indicador En espera debe disminuir tras pasar la cita a En atención");

  controller.sessionEnded();
});

test("HU-020: oculta Iniciar consulta si falta userId en sesión o veterinarianId en la cita", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const vet1 = "22222222-2222-4222-8222-222222222222";
  const apptWithVet = "aa000001-0000-4000-8000-000000000001";
  const apptWithoutVet = "aa000002-0000-4000-8000-000000000002";

  const items = [
    {
      id: apptWithVet,
      startsAt: "2099-09-28T14:00:00Z",
      endsAt: "2099-09-28T14:30:00Z",
      appointmentType: "CONSULTA_GENERAL",
      status: "EN_ESPERA",
      patientId: "55555555-5555-4555-8555-555555555555",
      patientName: "Thor",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet1,
      veterinarianFullName: "Dra. Elena Rojas"
    },
    {
      id: apptWithoutVet,
      startsAt: "2099-09-28T14:30:00Z",
      endsAt: "2099-09-28T15:00:00Z",
      appointmentType: "CONTROL",
      status: "EN_ESPERA",
      patientId: "66666666-6666-4666-8666-666666666666",
      patientName: "Milo",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: null,
      veterinarianFullName: "Sin asignar"
    }
  ];

  const controller = createAppointmentsController({
    document,
    client: {
      async requestJson(url) {
        if (url.startsWith("/api/v1/appointments/options")) {
          return {
            branches: [{ id: branchA, name: "Sede Principal", timeZone: "America/Lima" }],
            veterinarians: [{ id: vet1, fullName: "Dra. Elena Rojas", branchIds: [branchA] }]
          };
        }
        return {
          content: items,
          page: 0,
          size: 10,
          totalElements: items.length,
          totalPages: 1,
          scheduledCount: 0,
          confirmedCount: 0,
          waitingCount: 2,
          noShowCount: 0
        };
      }
    }
  });

  // 1. Sesión con CONSULTATION_CREATE pero sin userId: no muestra Iniciar consulta en ninguna cita
  controller.sessionReady({
    userId: null,
    authorities: ["APPOINTMENT_READ", "CONSULTATION_CREATE"]
  });
  await controller.openAppointmentsView();
  assert(document.querySelectorAll('[data-action="start-consultation"]').length === 0,
    "Sin userId en sesión no debe renderizar Iniciar consulta aunque tenga CONSULTATION_CREATE");

  // 2. Sesión con userId pero cita sin veterinarianId: no muestra Iniciar consulta en la cita sin vet
  controller.sessionReady({
    userId: vet1,
    authorities: ["APPOINTMENT_READ", "CONSULTATION_CREATE"]
  });
  await controller.openAppointmentsView();
  const rows = document.querySelectorAll("#appointments-table-body tr");
  assert(rows[0].querySelector('[data-action="start-consultation"]') !== null,
    "Cita con veterinarianId coincidente debe mostrar Iniciar consulta");
  assert(rows[1].querySelector('[data-action="start-consultation"]') === null,
    "Cita sin veterinarianId no debe mostrar Iniciar consulta");

  controller.sessionEnded();
});

test("HU-020: conserva confirmación de consulta y muestra error de agenda por separado si falla la recarga tras 201", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const vet1 = "22222222-2222-4222-8222-222222222222";
  const apptId = "aa000001-0000-4000-8000-000000000001";

  const items = [
    {
      id: apptId,
      startsAt: "2099-09-28T14:00:00Z",
      endsAt: "2099-09-28T14:30:00Z",
      appointmentType: "CONSULTA_GENERAL",
      status: "EN_ESPERA",
      patientId: "55555555-5555-4555-8555-555555555555",
      patientName: "Thor",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet1,
      veterinarianFullName: "Dra. Elena Rojas"
    }
  ];

  let postSucceeded = false;
  let failReload = false;

  const controller = createAppointmentsController({
    document,
    client: {
      async requestJson(url, options = {}) {
        if (options.method === "POST" && url.endsWith("/consultation")) {
          postSucceeded = true;
          failReload = true;
          return {
            id: "cc000001-0000-4000-8000-000000000001",
            appointmentId: apptId,
            appointmentStatus: "EN_ATENCION",
            patientId: "55555555-5555-4555-8555-555555555555",
            patientName: "Thor",
            status: "BORRADOR"
          };
        }
        if (url.startsWith("/api/v1/appointments/options")) {
          return {
            branches: [{ id: branchA, name: "Sede Principal", timeZone: "America/Lima" }],
            veterinarians: [{ id: vet1, fullName: "Dra. Elena Rojas", branchIds: [branchA] }]
          };
        }
        if (failReload) {
          throw new ApiRequestError({
            kind: "HTTP",
            status: 500,
            code: "SCHEDULE_LOAD_ERROR",
            message: "Error de conexión al recargar agenda.",
            correlationId: "corr-reload-500"
          });
        }
        return {
          content: items,
          page: 0,
          size: 10,
          totalElements: 1,
          totalPages: 1,
          scheduledCount: 0,
          confirmedCount: 0,
          waitingCount: 1,
          noShowCount: 0
        };
      }
    }
  });

  controller.sessionReady({
    userId: vet1,
    authorities: ["APPOINTMENT_READ", "CONSULTATION_CREATE"]
  });
  await controller.openAppointmentsView();

  const startBtn = document.querySelector('#appointments-table-body [data-action="start-consultation"]');
  assert(startBtn !== null, "Debe existir botón Iniciar consulta");

  startBtn.click();
  await new Promise((r) => setTimeout(r, 60));

  assert(postSucceeded, "El POST debió ejecutarse y responder 201/éxito");

  const noticeEl = document.querySelector("#appointments-notice");
  assert(noticeEl.hidden === false, "El mensaje de éxito de creación debe conservarse visible");
  assert(noticeEl.textContent.includes("Thor") && noticeEl.textContent.includes("borrador"),
    "El mensaje de éxito debe indicar que la consulta en borrador fue iniciada");

  const statusErrorEl = document.querySelector("#appointments-status-error");
  assert(statusErrorEl.hidden === false, "Debe mostrar error informando que falló la actualización de agenda");
  assert(statusErrorEl.textContent.includes("no se pudo actualizar la agenda"),
    "Debe indicar específicamente que la agenda no pudo actualizarse");
  assert(!statusErrorEl.textContent.includes("No fue posible iniciar la consulta"),
    "Nunca debe informar que falló el inicio de consulta tras un POST exitoso");
  assert(statusErrorEl.textContent.includes("corr-reload-500"),
    "El error de recarga debe incluir el correlationId");

  controller.sessionEnded();
});

test("HU-021: muestra Retomar consulta para cita en EN_ATENCION asignada al veterinario autenticado", async () => {
  const branchA = "11111111-1111-4111-8111-111111111111";
  const vet1 = "22222222-2222-4222-8222-222222222222";
  const vet2 = "33333333-3333-4333-8333-333333333333";
  const apptOwn = "aa000001-0000-4000-8000-000000000001";
  const apptOther = "aa000002-0000-4000-8000-000000000002";

  const items = [
    {
      id: apptOwn,
      startsAt: "2099-09-28T14:00:00Z",
      endsAt: "2099-09-28T14:30:00Z",
      appointmentType: "CONSULTA_GENERAL",
      status: "EN_ATENCION",
      patientId: "55555555-5555-4555-8555-555555555555",
      patientName: "Thor",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet1,
      veterinarianFullName: "Dra. Elena Rojas"
    },
    {
      id: apptOther,
      startsAt: "2099-09-28T14:30:00Z",
      endsAt: "2099-09-28T15:00:00Z",
      appointmentType: "CONSULTA_GENERAL",
      status: "EN_ATENCION",
      patientId: "66666666-6666-4666-8666-666666666666",
      patientName: "Loki",
      branchId: branchA,
      branchName: "Sede Principal",
      veterinarianId: vet2,
      veterinarianFullName: "Dr. Carlos Vega"
    }
  ];

  let postCalled = false;
  let resumedConsultation = null;

  const controller = createAppointmentsController({
    document,
    client: {
      async requestJson(url, options = {}) {
        if (options.method === "POST" && url === `/api/v1/appointments/${apptOwn}/consultation`) {
          postCalled = true;
          return {
            id: "cc000001-0000-4000-8000-000000000001",
            appointmentId: apptOwn,
            appointmentStatus: "EN_ATENCION",
            patientId: "55555555-5555-4555-8555-555555555555",
            patientName: "Thor",
            status: "BORRADOR"
          };
        }
        if (url.startsWith("/api/v1/appointments/options")) {
          return {
            branches: [{ id: branchA, name: "Sede Principal", timeZone: "America/Lima" }],
            veterinarians: [
              { id: vet1, fullName: "Dra. Elena Rojas", branchIds: [branchA] },
              { id: vet2, fullName: "Dr. Carlos Vega", branchIds: [branchA] }
            ]
          };
        }
        return {
          content: items,
          page: 0,
          size: 10,
          totalElements: 2,
          totalPages: 1,
          scheduledCount: 0,
          confirmedCount: 0,
          waitingCount: 0,
          noShowCount: 0
        };
      }
    },
    onStartConsultation: (consultation) => {
      resumedConsultation = consultation;
    }
  });

  controller.sessionReady({
    userId: vet1,
    authorities: ["APPOINTMENT_READ", "CONSULTATION_CREATE"]
  });

  await controller.openAppointmentsView();

  const rows = document.querySelectorAll("#appointments-table-body tr");
  assert(rows.length === 2, "Deben haber 2 filas renderizadas");

  // Fila propia en EN_ATENCION: muestra botón "Retomar consulta"
  const resumeBtn = rows[0].querySelector('[data-action="start-consultation"]');
  assert(resumeBtn !== null, "Fila propia en EN_ATENCION debe renderizar botón de acción de consulta");
  assert(resumeBtn.textContent.trim() === "Retomar consulta", "El botón debe mostrar 'Retomar consulta'");
  assert(resumeBtn.classList.contains("btn-outline-primary"), "Debe tener estilo btn-outline-primary");

  // Fila de otro veterinario en EN_ATENCION: NO muestra botón
  const otherActionBtn = rows[1].querySelector('[data-action="start-consultation"]');
  assert(otherActionBtn === null, "Fila de otro veterinario en EN_ATENCION no debe mostrar acción de consulta");

  // Al hacer clic, invoca POST y delega en onStartConsultation
  resumeBtn.click();
  await new Promise((r) => setTimeout(r, 40));

  assert(postCalled, "Debe invocar POST /api/v1/appointments/{id}/consultation para retomar");
  const noticeEl = document.querySelector("#appointments-notice");
  assert(!noticeEl.hidden, "Debe mostrar aviso accesible de consulta retomada");
  assert(noticeEl.textContent.includes("Consulta retomada para Thor"), "Aviso debe indicar que la consulta fue retomada");

  assert(resumedConsultation !== null, "Debe invocar onStartConsultation");
  assert(resumedConsultation.id === "cc000001-0000-4000-8000-000000000001", "La consulta retomada debe tener el ID correcto");

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
