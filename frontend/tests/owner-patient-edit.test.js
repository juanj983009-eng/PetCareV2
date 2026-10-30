import { ApiRequestError } from "../assets/js/api-client.js";
import { createOwnersController } from "../assets/js/owners-controller.js";
import { createPatientsController } from "../assets/js/patients-controller.js";

const resultsElement = document.querySelector("#test-results");

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function waitFor(predicate, timeoutMs = 1500) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("Timed out waiting for async UI condition.");
}

const sampleOwner = Object.freeze({
  id: "11111111-1111-4111-8111-111111111111",
  firstName: "Lucia",
  lastName: "Paredes",
  fullName: "Lucia Paredes",
  documentType: "DNI",
  documentNumber: "45879632",
  phone: "999111222",
  email: "lucia@example.test",
  address: "Av. Primavera 120",
  notificationsAccepted: true,
  status: "ACTIVO"
});

const sampleProfile = Object.freeze({
  id: "22222222-2222-4222-8222-222222222222",
  name: "Milo",
  species: "CANINO",
  breed: "Beagle",
  sex: "MACHO",
  birthDate: "2022-05-14",
  color: "Tricolor",
  microchip: "985141002345678",
  sterilized: false,
  status: "ACTIVO",
  owner: {
    id: sampleOwner.id,
    fullName: "Lucia Paredes",
    documentType: "DNI",
    documentNumber: "45879632",
    phone: "999111222",
    email: "lucia@example.test"
  },
  medicalRecord: {
    id: "33333333-3333-4333-8333-333333333333",
    allergies: "Alergia severa a penicilina",
    medicalAlerts: "Manejo con bozal",
    history: "Control preventivo anual.",
    createdAt: "2026-03-10T14:30:00Z"
  }
});

async function runTests() {
  const passed = [];
  let nextOwnerResponse = null;
  let nextPatientResponse = null;
  const calls = [];

  const client = {
    async requestJson(path, options = {}) {
      calls.push({ path, method: options.method ?? "GET", body: options.body ? JSON.parse(options.body) : null });
      if (path.startsWith("/api/v1/owners?")) {
        return nextOwnerResponse();
      }
      if (path.startsWith("/api/v1/owners/") && options.method === "PUT") {
        return nextOwnerResponse(options);
      }
      if (path.startsWith("/api/v1/patients?")) {
        return nextPatientResponse("search");
      }
      if (path.startsWith("/api/v1/patients/") && (!options.method || options.method === "GET")) {
        return nextPatientResponse("profile");
      }
      if (path.startsWith("/api/v1/patients/") && options.method === "PUT") {
        return nextPatientResponse("update", options);
      }
      throw new Error(`Unexpected path: ${path}`);
    }
  };

  const ownersController = createOwnersController({ document, client });
  const patientsController = createPatientsController({ document, client });

  // 1. RBAC visibility: without OWNER_UPDATE / PATIENT_UPDATE, edit actions are hidden
  ownersController.sessionReady({ authorities: ["OWNER_READ"] });
  patientsController.sessionReady({ authorities: ["PATIENT_READ"] });

  nextOwnerResponse = async () => ({
    content: [sampleOwner],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1
  });
  document.querySelector("#owners-nav-button").click();
  document.querySelector("#owner-search").value = "Lucia";
  document.querySelector("#owner-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => !document.querySelector("#owners-content").hidden);
  assert(
    document.querySelectorAll('[data-action="edit-owner"]').length === 0,
    "Los botones Editar propietario no deben renderizarse sin OWNER_UPDATE"
  );

  nextPatientResponse = async (kind) => {
    if (kind === "profile") return sampleProfile;
    return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
  };
  await patientsController.openPatientProfile(sampleProfile.id);
  assert(
    document.querySelector("#patient-profile-edit-button").hidden === true,
    "El botón Editar paciente debe permanecer oculto sin PATIENT_UPDATE"
  );
  passed.push("1. RBAC oculta acciones de edición sin OWNER_UPDATE ni PATIENT_UPDATE");

  // 2. RBAC visibility with OWNER_UPDATE and PATIENT_UPDATE + pre-loading & cancel without saving
  ownersController.sessionReady({ authorities: ["OWNER_READ", "OWNER_UPDATE"] });
  patientsController.sessionReady({ authorities: ["PATIENT_READ", "PATIENT_UPDATE"] });

  document.querySelector("#owners-nav-button").click();
  document.querySelector("#owner-search").value = "Lucia";
  document.querySelector("#owner-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => document.querySelectorAll('[data-action="edit-owner"]').length > 0);

  const editOwnerBtn = document.querySelector('#owners-table-body [data-action="edit-owner"]');
  assert(Boolean(editOwnerBtn), "Debe existir botón Editar propietario con OWNER_UPDATE");
  editOwnerBtn.click();

  const ownerDialog = document.querySelector("#owner-editor-dialog");
  assert(ownerDialog.open === true, "El modal de edición de propietario debe abrirse");
  assert(document.querySelector("#owner-editor-title").textContent === "Editar propietario", "Título del modal debe ser Editar propietario");
  assert(document.querySelector("#save-owner-button").textContent === "Guardar cambios", "Botón debe decir Guardar cambios");
  assert(document.querySelector("#owner-first-name").value === "Lucia", "Debe precargar nombres");
  assert(document.querySelector("#owner-last-name").value === "Paredes", "Debe precargar apellidos");
  assert(document.querySelector("#owner-document-type").value === "DNI", "Debe precargar tipo de documento");
  assert(document.querySelector("#owner-document-number").value === "45879632", "Debe precargar número de documento");
  assert(document.querySelector("#owner-phone").value === "999111222", "Debe precargar teléfono");
  assert(document.querySelector("#owner-email").value === "lucia@example.test", "Debe precargar correo");
  assert(document.querySelector("#owner-address").value === "Av. Primavera 120", "Debe precargar dirección");
  assert(document.querySelector("#owner-notifications").checked === true, "Debe precargar preferencia de notificaciones");

  const callsBeforeOwnerCancel = calls.length;
  document.querySelector("#cancel-owner-button").click();
  assert(ownerDialog.open === false, "Cancelar debe cerrar el modal de propietario");
  assert(calls.length === callsBeforeOwnerCancel, "Cancelar no debe enviar solicitud PUT");
  passed.push("2. Precarga de propietario y cancelación sin guardar");

  // 3. Owner validation, 409 conflict preserving input, duplicate phone confirmation, and success update
  editOwnerBtn.click();
  document.querySelector("#owner-first-name").value = "";
  document.querySelector("#owner-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  assert(!document.querySelector("#owner-form-validation").hidden, "Debe mostrar error de validación cuando faltan campos");
  assert(document.querySelector("#owner-first-name").getAttribute("aria-invalid") === "true", "Debe marcar aria-invalid");

  document.querySelector("#owner-first-name").value = "Lucia Elena <img src=x onerror=alert(1)>";
  document.querySelector("#owner-phone").value = "988777666";

  // Simulate 409 document conflict preserving inputs
  nextOwnerResponse = async () => {
    throw new ApiRequestError({
      status: 409,
      code: "OWNER_DOCUMENT_ALREADY_EXISTS",
      message: "Ya existe otro propietario con ese documento en esta clínica."
    });
  };
  document.querySelector("#owner-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => !document.querySelector("#owner-form-error").hidden);
  assert(ownerDialog.open === true, "El modal debe permanecer abierto ante conflicto 409");
  assert(
    document.querySelector("#owner-first-name").value === "Lucia Elena <img src=x onerror=alert(1)>",
    "El valor editado debe preservarse tras un error"
  );

  // Simulate duplicate phone 409 -> window.confirm -> confirmPhoneDuplicate: true -> success
  let ownerPutCount = 0;
  window.confirm = () => true;
  nextOwnerResponse = async (options) => {
    if (!options) {
      return {
        content: [{
          ...sampleOwner,
          firstName: "Lucia Elena <img src=x onerror=alert(1)>",
          fullName: "Lucia Elena <img src=x onerror=alert(1)> Paredes",
          phone: "988777666"
        }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1
      };
    }
    ownerPutCount += 1;
    const payload = JSON.parse(options.body);
    if (!payload.confirmPhoneDuplicate) {
      throw new ApiRequestError({
        status: 409,
        code: "OWNER_PHONE_ALREADY_EXISTS",
        message: "El teléfono ya está asociado a otro propietario."
      });
    }
    return {
      ...sampleOwner,
      firstName: payload.firstName,
      fullName: `${payload.firstName} ${payload.lastName}`,
      phone: payload.phone
    };
  };

  document.querySelector("#owner-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => ownerDialog.open === false);
  assert(ownerPutCount === 2, "Debe reenviar PUT con confirmPhoneDuplicate=true");
  assert(!document.querySelector("#owner-notice").hidden, "Debe mostrar aviso de éxito de propietario");
  assert(
    document.querySelector("#owners-table-body").querySelectorAll("img").length === 0,
    "No debe inyectar HTML hostil en la tabla de propietarios"
  );
  passed.push("3. Validación, conflicto 409 preservando valores, confirmación de teléfono duplicado y actualización exitosa de propietario");

  // 4. Patient edit: pre-load, cancel, 409 microchip conflict preserving values, and successful PUT refreshing profile
  nextPatientResponse = async (kind) => {
    if (kind === "profile") return sampleProfile;
    return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
  };
  await patientsController.openPatientProfile(sampleProfile.id);
  const editPatientBtn = document.querySelector("#patient-profile-edit-button");
  assert(editPatientBtn.hidden === false, "El botón Editar paciente debe mostrarse con PATIENT_UPDATE");

  editPatientBtn.click();
  const patientDialog = document.querySelector("#patient-editor-dialog");
  assert(patientDialog.open === true, "El modal de edición de paciente debe abrirse");
  assert(document.querySelector("#patient-editor-title").textContent === "Editar paciente", "Título debe ser Editar paciente");
  assert(document.querySelector("#save-patient-button").textContent === "Guardar cambios", "Botón debe decir Guardar cambios");
  assert(document.querySelector("#patient-owner-id").value === sampleOwner.id, "Debe precargar ownerId actual");
  assert(document.querySelector("#patient-name").value === "Milo", "Debe precargar nombre del paciente");
  assert(document.querySelector("#patient-species").value === "CANINO", "Debe precargar especie");
  assert(document.querySelector("#patient-breed").value === "Beagle", "Debe precargar raza");
  assert(document.querySelector("#patient-sex").value === "MACHO", "Debe precargar sexo");
  assert(document.querySelector("#patient-birth-date").value === "2022-05-14", "Debe precargar fecha de nacimiento");
  assert(document.querySelector("#patient-color").value === "Tricolor", "Debe precargar color");
  assert(document.querySelector("#patient-microchip").value === "985141002345678", "Debe precargar microchip");
  assert(document.querySelector("#patient-sterilized").checked === false, "Debe precargar esterilizado");

  // Cancel without saving
  const callsBeforePatientCancel = calls.length;
  document.querySelector("#cancel-patient-button").click();
  assert(patientDialog.open === false, "Cancelar debe cerrar el modal de paciente");
  assert(calls.length === callsBeforePatientCancel, "Cancelar paciente no debe enviar PUT");

  // Reopen, trigger 409 microchip conflict preserving input, then succeed
  editPatientBtn.click();
  document.querySelector("#patient-name").value = "Milo Editado";
  document.querySelector("#patient-color").value = "Bicolor <script>alert(1)</script>";
  document.querySelector("#patient-sterilized").checked = true;

  nextPatientResponse = async (kind) => {
    if (kind === "update") {
      throw new ApiRequestError({
        status: 409,
        code: "PATIENT_MICROCHIP_ALREADY_EXISTS",
        message: "Ya existe otro paciente con ese microchip en esta clínica."
      });
    }
    return sampleProfile;
  };

  document.querySelector("#patient-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => !document.querySelector("#patient-form-error").hidden);
  assert(patientDialog.open === true, "El modal de paciente debe permanecer abierto tras conflicto 409");
  assert(document.querySelector("#patient-name").value === "Milo Editado", "El nombre editado debe preservarse");

  nextPatientResponse = async (kind, options) => {
    if (kind === "update") {
      const payload = JSON.parse(options.body);
      return {
        id: sampleProfile.id,
        ownerId: payload.ownerId,
        name: payload.name,
        species: payload.species,
        breed: payload.breed,
        sex: payload.sex,
        birthDate: payload.birthDate,
        color: payload.color,
        microchip: payload.microchip,
        sterilized: payload.sterilized,
        status: "ACTIVO"
      };
    }
    if (kind === "profile") {
      return {
        ...sampleProfile,
        name: "Milo Editado",
        color: "Bicolor <script>alert(1)</script>",
        sterilized: true
      };
    }
    return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
  };

  document.querySelector("#patient-editor-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => patientDialog.open === false);
  await waitFor(() => document.querySelector("#patient-profile-title").textContent === "Milo Editado");
  assert(!document.querySelector("#patient-profile-notice").hidden, "Debe mostrar confirmación visual en el perfil del paciente");
  assert(document.querySelector("#patient-profile-sterilized").textContent === "Sí", "El perfil debe reflejar esterilizado = Sí");
  assert(
    document.querySelector("#patient-profile-color").textContent === "Bicolor <script>alert(1)</script>",
    "El texto hostil debe mostrarse como texto literal"
  );
  assert(
    document.querySelector("#patient-profile-view").querySelectorAll("script").length === 0,
    "No debe ejecutar ni insertar etiquetas script en el perfil"
  );
  passed.push("4. Precarga, cancelación, conflicto 409 y actualización exitosa del paciente en perfil clínico");

  nextOwnerResponse = async () => ({
    content: [sampleOwner, { ...sampleOwner, id: "other-owner", phone: null, email: null, documentNumber: null }],
    page: 0, size: 10, totalElements: 23, totalPages: 3
  });
  document.querySelector("#owner-search").value = "Lucia";
  document.querySelector("#owner-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => document.querySelector("#owners-summary-total").textContent === "23");
  assert(document.querySelector("#owners-summary-phone").textContent === "1", "Teléfonos debe contar solo la página recibida");
  assert(document.querySelector("#owners-summary-email").textContent === "1", "Correos debe omitir valores nulos");
  assert(document.querySelector("#owners-summary-document").textContent === "1", "Documentos debe omitir valores nulos");
  assert(!document.querySelector("#owners-overview").hidden, "El resumen debe mostrarse tras buscar");
  assert(document.querySelectorAll("#owners-cards .pc-owner-avatar").length === 2, "Las fichas móviles deben incluir iniciales");
  ownersController.sessionEnded();
  assert(document.querySelector("#owners-overview").hidden, "Cerrar sesión debe ocultar el resumen");
  passed.push("5. Resumen distingue total filtrado de contactos en página y se limpia al cerrar sesión");

  ownersController.sessionReady({ authorities: ["OWNER_READ"] });
  const beforeOpen = calls.length;
  document.querySelector("#owners-nav-button").click();
  assert(calls.length === beforeOpen, "Abrir Propietarios no debe consultar automáticamente");
  nextOwnerResponse = async () => ({ content: [sampleOwner], page: 0, size: 6, totalElements: 8, totalPages: 2 });
  document.querySelector("#owner-search").value = "";
  document.querySelector("#owner-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => !document.querySelector("#owners-content").hidden);
  let params = new URL(calls.at(-1).path, "http://test.local").searchParams;
  assert(params.get("query") === "" && params.get("size") === "6", "Buscar vacío debe solicitar todos con tamaño 6");
  nextOwnerResponse = async () => ({ content: [sampleOwner], page: 1, size: 6, totalElements: 8, totalPages: 2 });
  document.querySelector("#owners-next-page").click();
  await waitFor(() => document.querySelector("#owners-page-status").textContent.startsWith("Página 2 de"));
  params = new URL(calls.at(-1).path, "http://test.local").searchParams;
  assert(params.get("page") === "1" && params.get("query") === "", "Siguiente debe conservar la búsqueda vacía");
  const beforeInvalid = calls.length;
  document.querySelector("#owner-search").value = " x ";
  document.querySelector("#owner-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  assert(calls.length === beforeInvalid, "Un carácter no debe enviar HTTP");
  assert(document.querySelector("#owner-search").getAttribute("aria-invalid") === "true", "Validación debe ser accesible");
  document.querySelector("#owner-search").value = "  ";
  nextOwnerResponse = async () => ({ content: [sampleOwner], page: 0, size: 6, totalElements: 1, totalPages: 1 });
  document.querySelector("#owner-search-form").dispatchEvent(new Event("submit", { cancelable: true }));
  await waitFor(() => calls.length > beforeInvalid && !document.querySelector("#owners-content").hidden);
  assert(document.querySelector("#owner-search").checkValidity(), "Vaciar debe recuperar la validez tras un error");
  passed.push("6. Búsqueda vacía explícita, paginación de seis y rechazo de un carácter");

  window.__PETCARE_TEST_RESULTS__ = {
    passed: passed.length,
    failed: 0,
    details: passed
  };
  resultsElement.textContent = `PASS (${passed.length}/${passed.length})\n${passed.join("\n")}`;
}

runTests().catch((error) => {
  window.__PETCARE_TEST_RESULTS__ = {
    passed: 0,
    failed: 1,
    error: error.message
  };
  resultsElement.textContent = `FAIL: ${error.message}\n${error.stack ?? ""}`;
});
