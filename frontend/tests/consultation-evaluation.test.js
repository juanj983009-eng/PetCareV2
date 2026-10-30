import { ApiRequestError } from "../assets/js/api-client.js";
import { createConsultationEvaluationController } from "../assets/js/consultation-evaluation-controller.js";

const CONSULTATION_ID = "11111111-aaaa-4111-8111-111111111111";
const VET_ID = "22222222-bbbb-4222-8222-222222222222";
const tests = [];

test("el retrato de demo se vincula por ID y se limpia al cambiar de paciente, fallar la imagen o cerrar sesión", async () => {
  let patientId = "e949393f-77aa-4e49-8d27-bddcbd4e0fbc";
  const client = { requestJson: async () => ({ id: CONSULTATION_ID, patientId, patientName: "Paciente de prueba", status: "FINALIZADA", diagnoses: [], prescriptions: [] }) };
  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  const avatar = document.querySelector("#evaluation-context-avatar");
  controller.sessionReady({ authorities: ["CONSULTATION_READ"] });
  await controller.openEvaluation(CONSULTATION_ID);
  const oldImage = avatar.querySelector("img");
  assert(oldImage?.getAttribute("src").endsWith("demo-luna.webp"), "El UUID de Luna debe usar su retrato de demo");
  patientId = "patient-without-demo-portrait";
  await controller.openEvaluation("other-consultation");
  oldImage.dispatchEvent(new Event("error"));
  assert(!avatar.querySelector("img"), "Otro paciente no debe heredar el retrato anterior");
  assert(avatar.textContent.includes("Avatar original"), "Debe recuperar el avatar original");
  patientId = "e949393f-77aa-4e49-8d27-bddcbd4e0fbc";
  await controller.openEvaluation(CONSULTATION_ID);
  avatar.querySelector("img").dispatchEvent(new Event("error"));
  assert(!avatar.querySelector("img"), "Un recurso fallido debe recuperar el avatar original");
  await controller.openEvaluation(CONSULTATION_ID);
  controller.sessionEnded();
  assert(!avatar.querySelector("img"), "Cerrar sesión debe retirar el retrato");
  controller.destroy();
});

test("sin CONSULTATION_READ bloquea la vista con estado forbidden y no realiza peticiones", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({ authorities: ["APPOINTMENT_READ"] });
  await controller.openEvaluation(CONSULTATION_ID);

  assert(!document.querySelector("#consultation-evaluation-view").hidden, "Debe abrir #consultation-evaluation-view");
  assert(!document.querySelector("#evaluation-forbidden").hidden, "Debe mostrar #evaluation-forbidden");
  assert(document.querySelector("#evaluation-content-container").hidden, "El contenido debe estar oculto");
  equal(calls.length, 0);

  controller.destroy();
});

test("carga exitosa vía GET: puebla contexto, valores previos de evaluación y habilita edición en BORRADOR", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          appointmentId: "33333333-cccc-4333-8333-333333333333",
          patientId: "44444444-dddd-4444-8444-444444444444",
          medicalRecordId: "55555555-eeee-4555-8555-555555555555",
          branchId: "66666666-ffff-4666-8666-666666666666",
          veterinarianId: VET_ID,
          patientName: "Kira",
          veterinarianFullName: "Dra. Sofía Morales",
          branchName: "Sede Miraflores",
          status: "BORRADOR",
          reason: "Control postquirúrgico y revisión de sutura",
          anamnesis: "Paciente con apetito normal y buen ánimo.",
          physicalExam: "Herida quirúrgica limpia sin signos de infección.",
          diagnoses: [],
          clinicalDetails: {
            vitalSigns: {
              temperatureCelsius: 38.6,
              weightKg: 14.2,
              heartRateBpm: 105,
              respiratoryRateBpm: 22,
              capillaryRefillTimeSeconds: 1.5,
              mucousMembranes: "ROSADAS"
            },
            observations: "Retirar puntos en 3 días."
          }
        };
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  equal(calls.length, 1);
  equal(calls[0], `/api/v1/consultations/${CONSULTATION_ID}`);

  assert(!document.querySelector("#consultation-evaluation-view").hidden, "Vista debe ser visible");
  assert(!document.querySelector("#evaluation-content-container").hidden, "Contenedor de formulario debe ser visible");
  equal(document.querySelector("#evaluation-context-patient").textContent, "Kira");
  equal(document.querySelector("#evaluation-context-veterinarian").textContent, "Dra. Sofía Morales");
  equal(document.querySelector("#evaluation-context-branch").textContent, "Sede Miraflores");
  equal(document.querySelector("#evaluation-context-status").textContent, "BORRADOR");

  equal(document.querySelector("#evaluation-reason").value, "Control postquirúrgico y revisión de sutura");
  equal(document.querySelector("#evaluation-anamnesis").value, "Paciente con apetito normal y buen ánimo.");
  equal(document.querySelector("#evaluation-physical-exam").value, "Herida quirúrgica limpia sin signos de infección.");
  equal(document.querySelector("#evaluation-temperature").value, "38.6");
  equal(document.querySelector("#evaluation-weight").value, "14.2");
  equal(document.querySelector("#evaluation-heart-rate").value, "105");
  equal(document.querySelector("#evaluation-respiratory-rate").value, "22");
  equal(document.querySelector("#evaluation-crt").value, "1.5");
  equal(document.querySelector("#evaluation-mucous-membranes").value, "ROSADAS");
  equal(document.querySelector("#evaluation-observations").value, "Retirar puntos en 3 días.");

  assert(!document.querySelector("#evaluation-reason").disabled, "El formulario debe ser editable");
  assert(!document.querySelector("#evaluation-save-button").disabled, "El botón guardar debe estar habilitado");

  controller.destroy();
});

test("validación en cliente: detecta campos inválidos, marca aria-invalid y no envía solicitud PUT", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      return {
        id: CONSULTATION_ID,
        status: "BORRADOR",
        reason: "Inicial"
      };
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);
  calls.length = 0; // Limpiar llamadas tras apertura

  // Vaciar motivo (obligatorio) y poner temperatura fuera de rango
  document.querySelector("#evaluation-reason").value = "";
  document.querySelector("#evaluation-temperature").value = "55.0";

  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  await flushPromises();

  equal(calls.length, 0, "No debe realizar PUT cuando hay errores de validación");
  assert(!document.querySelector("#evaluation-validation").hidden, "Debe mostrar alerta de validación");
  equal(document.querySelector("#evaluation-reason").getAttribute("aria-invalid"), "true");
  equal(document.querySelector("#evaluation-temperature").getAttribute("aria-invalid"), "true");

  controller.destroy();
});

test("guardado exitoso vía PUT: envía payload estructurado y muestra alerta accesible de éxito", async () => {
  const calls = [];
  let sentBody = null;

  const client = {
    requestJson: async (url, options = {}) => {
      calls.push({ url, method: options.method || "GET" });
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Motivo inicial",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/evaluation` && options.method === "PUT") {
        sentBody = JSON.parse(options.body);
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: sentBody.reason,
          anamnesis: sentBody.anamnesis,
          physicalExam: sentBody.physicalExam,
          clinicalDetails: {
            vitalSigns: sentBody.vitalSigns,
            observations: sentBody.observations
          }
        };
      }
      throw new Error(`URL o método no manejado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  document.querySelector("#evaluation-reason").value = "Evaluación dermatológica y control general";
  document.querySelector("#evaluation-anamnesis").value = "Prurito leve en zona lumbar.";
  document.querySelector("#evaluation-physical-exam").value = "Lesión alopécica focal sin sangrado.";
  document.querySelector("#evaluation-temperature").value = "38.8";
  document.querySelector("#evaluation-weight").value = "10.5";
  document.querySelector("#evaluation-heart-rate").value = "120";
  document.querySelector("#evaluation-respiratory-rate").value = "28";
  document.querySelector("#evaluation-crt").value = "1.0";
  document.querySelector("#evaluation-mucous-membranes").value = "ROSADAS";
  document.querySelector("#evaluation-observations").value = "Indicar shampoo medicado dos veces por semana.";

  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  await flushPromises();

  assert(sentBody !== null, "Debe haber enviado cuerpo en el PUT");
  equal(sentBody.reason, "Evaluación dermatológica y control general");
  equal(sentBody.anamnesis, "Prurito leve en zona lumbar.");
  equal(sentBody.physicalExam, "Lesión alopécica focal sin sangrado.");
  equal(sentBody.vitalSigns.temperatureCelsius, 38.8);
  equal(sentBody.vitalSigns.weightKg, 10.5);
  equal(sentBody.vitalSigns.heartRateBpm, 120);
  equal(sentBody.vitalSigns.respiratoryRateBpm, 28);
  equal(sentBody.vitalSigns.capillaryRefillTimeSeconds, 1.0);
  equal(sentBody.vitalSigns.mucousMembranes, "ROSADAS");
  equal(sentBody.observations, "Indicar shampoo medicado dos veces por semana.");

  assert(!document.querySelector("#evaluation-notice").hidden, "Debe mostrar alerta de éxito");
  assert(document.querySelector("#evaluation-notice").textContent.includes("correctamente"), "Mensaje de éxito confirmado");
  assert(document.querySelector("#evaluation-error").hidden, "No debe haber alerta de error");

  controller.destroy();
});

test("conservación de datos del formulario en caso de error del servidor o rechazo", async () => {
  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Motivo inicial",
          diagnoses: []
        };
      }
      if (options.method === "PUT") {
        throw new ApiRequestError({
          status: 400,
          code: "INVALID_ARGUMENT",
          message: "Los datos de la evaluación no son válidos",
          correlationId: "corr-err-1234",
          fieldErrors: [{ field: "temperatureCelsius", message: "Temperatura fuera de rango clínico" }]
        });
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  document.querySelector("#evaluation-reason").value = "Valor conservado en motivo";
  document.querySelector("#evaluation-anamnesis").value = "Valor conservado en anamnesis";
  document.querySelector("#evaluation-temperature").value = "39.0";

  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  await flushPromises();

  // El valor escrito por el usuario NO debe borrarse
  equal(document.querySelector("#evaluation-reason").value, "Valor conservado en motivo");
  equal(document.querySelector("#evaluation-anamnesis").value, "Valor conservado en anamnesis");
  equal(document.querySelector("#evaluation-temperature").value, "39.0");

  assert(!document.querySelector("#evaluation-validation").hidden, "Debe mostrar resumen de error de validación");
  equal(document.querySelector("#evaluation-temperature").getAttribute("aria-invalid"), "true");

  controller.destroy();
});

test("prevención de doble envío: estado de carga y botón bloqueado durante PUT", async () => {
  let resolvePut;
  const putPromise = new Promise((resolve) => { resolvePut = resolve; });

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return { id: CONSULTATION_ID, status: "BORRADOR", reason: "Motivo" };
      }
      if (options.method === "PUT") {
        return putPromise;
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  // En progreso
  const saveBtn = document.querySelector("#evaluation-save-button");
  assert(saveBtn.disabled, "El botón debe estar deshabilitado mientras se guarda");
  equal(saveBtn.getAttribute("aria-busy"), "true");
  assert(!document.querySelector("#evaluation-save-spinner").hidden, "Spinner debe estar visible");

  // Resolver PUT
  resolvePut({
    id: CONSULTATION_ID,
    status: "BORRADOR",
    reason: "Motivo"
  });

  await flushPromises();

  assert(!saveBtn.disabled, "El botón vuelve a estar habilitado");
  equal(saveBtn.getAttribute("aria-busy"), "false");
  assert(document.querySelector("#evaluation-save-spinner").hidden, "Spinner debe ocultarse");

  controller.destroy();
});

test("modo de solo lectura si la consulta ya no está en BORRADOR o falta CONSULTATION_UPDATE", async () => {
  const client = {
    requestJson: async () => ({
      id: CONSULTATION_ID,
      status: "FINALIZADA",
      reason: "Consulta ya concluida",
      anamnesis: "Anamnesis cerrada"
    })
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ"] // Sin CONSULTATION_UPDATE
  });

  await controller.openEvaluation(CONSULTATION_ID);

  assert(document.querySelector("#evaluation-reason").disabled, "El motivo debe estar deshabilitado");
  assert(document.querySelector("#evaluation-anamnesis").disabled, "La anamnesis debe estar deshabilitada");
  assert(document.querySelector("#evaluation-temperature").disabled, "La temperatura debe estar deshabilitada");
  assert(document.querySelector("#evaluation-save-button").hidden, "El botón guardar debe estar oculto");

  controller.destroy();
});

test("botón Volver a la agenda invoca onBackToAppointments y oculta la vista", async () => {
  let backInvoked = false;
  const client = {
    requestJson: async () => ({ id: CONSULTATION_ID, status: "BORRADOR", reason: "Motivo" })
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {
      backInvoked = true;
    }
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);
  assert(!document.querySelector("#consultation-evaluation-view").hidden);

  document.querySelector("#consultation-evaluation-back-button").click();
  assert(document.querySelector("#consultation-evaluation-view").hidden, "La vista debe ocultarse al volver");
  assert(backInvoked, "El callback de regreso debe haberse invocado");

  controller.destroy();
});

test("PUT envía cabecera Content-Type application/json verificada a nivel HTTP fetch", async () => {
  let capturedHeaders = null;
  let capturedBody = null;
  let capturedMethod = null;

  const originalFetch = window.fetch;
  window.fetch = async (url, options = {}) => {
    capturedMethod = options.method;
    capturedHeaders = options.headers;
    capturedBody = options.body;
    return {
      ok: true,
      status: 200,
      headers: new Headers({ "content-type": "application/json" }),
      text: async () => JSON.stringify({
        id: CONSULTATION_ID,
        status: "BORRADOR",
        reason: "Motivo verificado en cabeceras"
      })
    };
  };

  try {
    const client = {
      requestJson: async (url, options = {}) => {
        const response = await window.fetch(url, options);
        return JSON.parse(await response.text());
      }
    };

    const controller = createConsultationEvaluationController({
      document,
      client,
      onBackToAppointments: () => {}
    });

    controller.sessionReady({
      userId: VET_ID,
      authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
    });

    await controller.openEvaluation(CONSULTATION_ID);

    document.querySelector("#evaluation-reason").value = "Motivo verificado en cabeceras";
    const form = document.querySelector("#consultation-evaluation-form");
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

    await flushPromises();

    equal(capturedMethod, "PUT");
    assert(capturedHeaders !== null, "Debe enviar cabeceras");
    equal(capturedHeaders["Content-Type"], "application/json");
    assert(capturedBody !== null, "Debe enviar cuerpo JSON");
    const parsedBody = JSON.parse(capturedBody);
    equal(parsedBody.reason, "Motivo verificado en cabeceras");

    controller.destroy();
  } finally {
    window.fetch = originalFetch;
  }
});

test("carga segura entre consultas: abrir A y fallar GET B limpia datos de A y bloquea formulario impidiendo guardar datos de A en B", async () => {
  const CONSULTATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const CONSULTATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const putCalls = [];

  const client = {
    requestJson: async (url, options = {}) => {
      if (options.method === "PUT") {
        putCalls.push({ url, body: options.body });
        return {};
      }
      if (url === `/api/v1/consultations/${CONSULTATION_A}`) {
        return {
          id: CONSULTATION_A,
          status: "BORRADOR",
          patientName: "Paciente A",
          reason: "Razón de Consulta A",
          anamnesis: "Anamnesis confidencial de Paciente A"
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B}`) {
        throw new ApiRequestError({
          status: 404,
          code: "CONSULTATION_NOT_FOUND",
          message: "Consulta B no encontrada"
        });
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  // 1. Abrir Consulta A exitosamente
  await controller.openEvaluation(CONSULTATION_A);
  equal(document.querySelector("#evaluation-reason").value, "Razón de Consulta A");
  equal(document.querySelector("#evaluation-anamnesis").value, "Anamnesis confidencial de Paciente A");
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente A");
  assert(!document.querySelector("#evaluation-save-button").disabled);

  // 2. Intentar abrir Consulta B cuyo GET falla con 404
  await controller.openEvaluation(CONSULTATION_B);

  // 3. Verificar que los datos de Consulta A fueron completamente purgados
  equal(document.querySelector("#evaluation-reason").value, "");
  equal(document.querySelector("#evaluation-anamnesis").value, "");
  equal(document.querySelector("#evaluation-context-patient").textContent, "—");
  assert(document.querySelector("#evaluation-content-container").hidden, "El contenedor de formulario debe estar oculto tras fallo");
  assert(!document.querySelector("#evaluation-not-found").hidden, "Debe mostrar estado not-found");

  // 4. Verificar que el formulario y botón guardar están deshabilitados
  assert(document.querySelector("#evaluation-reason").disabled, "El motivo debe estar deshabilitado");
  assert(document.querySelector("#evaluation-save-button").disabled, "El botón guardar debe estar deshabilitado");

  // 5. Intentar enviar el formulario por evento directo submit
  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  // 6. Asegurar que NUNCA se envió PUT a Consulta B con los datos de A
  equal(putCalls.length, 0, "No se debe permitir enviar PUT cuando la consulta falló en cargar");

  controller.destroy();
});

test("protección contra respuestas tardías: respuesta lenta de GET A no sobreescribe la consulta B", async () => {
  const CONSULTATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const CONSULTATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  let resolveGetA;
  const getAPromise = new Promise((r) => { resolveGetA = r; });

  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_A}`) {
        return getAPromise;
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B}`) {
        return {
          id: CONSULTATION_B,
          status: "BORRADOR",
          patientName: "Paciente B",
          reason: "Razón Consulta B"
        };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  // Solicitar A (lenta)
  const openAPromise = controller.openEvaluation(CONSULTATION_A);

  // Inmediatamente solicitar B (rápida)
  await controller.openEvaluation(CONSULTATION_B);
  equal(document.querySelector("#evaluation-reason").value, "Razón Consulta B");
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente B");

  // Ahora resuelve tardíamente GET A
  resolveGetA({
    id: CONSULTATION_A,
    status: "BORRADOR",
    patientName: "Paciente A",
    reason: "Razón Consulta A TARDÍA"
  });
  await openAPromise;
  await flushPromises();

  // El formulario y contexto deben seguir mostrando estrictamente la consulta B
  equal(document.querySelector("#evaluation-reason").value, "Razón Consulta B");
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente B");

  controller.destroy();
});

test("cierre de sesión limpia formulario, contexto y descarta respuestas GET en vuelo", async () => {
  let resolveGet;
  const getPromise = new Promise((r) => { resolveGet = r; });

  const client = {
    requestJson: async () => getPromise
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  const openPromise = controller.openEvaluation(CONSULTATION_ID);

  // Cerrar sesión mientras GET está en vuelo
  controller.sessionEnded();

  assert(document.querySelector("#consultation-evaluation-view").hidden, "La vista debe ocultarse al cerrar sesión");
  equal(document.querySelector("#evaluation-reason").value, "");
  assert(document.querySelector("#evaluation-save-button").disabled);

  // Resuelve GET tardíamente
  resolveGet({
    id: CONSULTATION_ID,
    status: "BORRADOR",
    reason: "Datos que no deben verse tras logout"
  });
  await openPromise;
  await flushPromises();

  equal(document.querySelector("#evaluation-reason").value, "", "No debe poblar formulario si la sesión terminó");
  assert(document.querySelector("#consultation-evaluation-view").hidden);

  controller.destroy();
});

test("conserva variableDetails existentes cuando el usuario actualiza otros campos de la evaluación", async () => {
  let sentPayload = null;

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Motivo inicial",
          clinicalDetails: {
            vitalSigns: null,
            observations: "Observaciones previas",
            variableDetails: {
              "customTag": "VIP_PATIENT",
              "temperament": "NERVOUS"
            }
          }
        };
      }
      if (options.method === "PUT") {
        sentPayload = JSON.parse(options.body);
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: sentPayload.reason,
          clinicalDetails: {
            observations: sentPayload.observations,
            variableDetails: sentPayload.variableDetails
          }
        };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  // Modificar únicamente el motivo
  document.querySelector("#evaluation-reason").value = "Motivo modificado";

  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(sentPayload !== null, "Debe enviar payload PUT");
  equal(sentPayload.reason, "Motivo modificado");
  assert(sentPayload.variableDetails !== null, "variableDetails no debe ser null");
  equal(sentPayload.variableDetails["customTag"], "VIP_PATIENT");
  equal(sentPayload.variableDetails["temperament"], "NERVOUS");

  controller.destroy();
});

test("concurrencia y estado: error 409 por cambio de estado a no borrador deshabilita formulario y muestra mensaje de conflicto", async () => {
  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Motivo borrador"
        };
      }
      if (options.method === "PUT") {
        throw new ApiRequestError({
          status: 409,
          code: "INVALID_STATE",
          message: "La consulta no se encuentra en estado borrador.",
          correlationId: "corr-conflict-409"
        });
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);
  assert(!document.querySelector("#evaluation-reason").disabled);

  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(!document.querySelector("#evaluation-error").hidden, "Debe mostrar error");
  assert(document.querySelector("#evaluation-error-message").textContent.includes("no se encuentra en estado borrador"));
  assert(document.querySelector("#evaluation-reason").disabled, "El formulario debe bloquearse tras conflicto 409");
  assert(document.querySelector("#evaluation-save-button").disabled, "El botón guardar debe deshabilitarse");

  controller.destroy();
});

test("cruce de guardado: PUT de A exitoso después de abrir B no altera estado, controles, mensajes ni datos de B", async () => {
  const CONSULTATION_A = "11111111-1111-4111-8111-111111111111";
  const CONSULTATION_B = "22222222-2222-4222-8222-222222222222";
  let resolvePutA;
  const putAPromise = new Promise((resolve) => { resolvePutA = resolve; });

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_A}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_A,
          status: "BORRADOR",
          patientName: "Paciente A",
          veterinarianFullName: "Dr. Veterinario A",
          reason: "Motivo Consulta A",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_B,
          status: "BORRADOR",
          patientName: "Paciente B",
          veterinarianFullName: "Dra. Veterinaria B",
          reason: "Motivo Consulta B",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_A}/evaluation` && options.method === "PUT") {
        return putAPromise;
      }
      throw new Error(`URL no manejada: ${url} (${options.method})`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  // 1. Abrir Consulta A
  await controller.openEvaluation(CONSULTATION_A);
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente A");
  equal(document.querySelector("#evaluation-reason").value, "Motivo Consulta A");

  // 2. Iniciar guardado de A (PUT en vuelo)
  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  // 3. Abrir Consulta B antes de que responda el PUT de A
  await controller.openEvaluation(CONSULTATION_B);
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente B");
  equal(document.querySelector("#evaluation-reason").value, "Motivo Consulta B");
  assert(!document.querySelector("#evaluation-save-button").disabled, "El botón guardar de B debe estar habilitado");
  assert(document.querySelector("#evaluation-notice").hidden, "El aviso de éxito no debe mostrarse antes de resolver");

  // 4. Responder tardíamente con éxito el PUT de A
  resolvePutA({
    id: CONSULTATION_A,
    status: "BORRADOR",
    patientName: "Paciente A",
    reason: "Motivo Consulta A Guardado"
  });
  await flushPromises();

  // 5. Verificar que la consulta B permanece completamente inalterada
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente B");
  equal(document.querySelector("#evaluation-reason").value, "Motivo Consulta B");
  assert(!document.querySelector("#evaluation-save-button").disabled, "El botón guardar de B debe continuar habilitado");
  equal(document.querySelector("#evaluation-save-button").getAttribute("aria-busy"), "false");
  assert(document.querySelector("#evaluation-save-spinner").hidden, "El spinner de guardado debe estar oculto");
  assert(document.querySelector("#evaluation-notice").hidden, "El aviso de éxito de A no debe mostrarse en B");
  assert(document.querySelector("#evaluation-error").hidden, "No debe haber mensaje de error en B");

  controller.destroy();
});

test("cruce de guardado: PUT de A fallido (409 conflicto o error) después de abrir B no bloquea ni muestra error en B", async () => {
  const CONSULTATION_A = "11111111-1111-4111-8111-111111111111";
  const CONSULTATION_B = "22222222-2222-4222-8222-222222222222";
  let rejectPutA;
  const putAPromise = new Promise((_, reject) => { rejectPutA = reject; });

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_A}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_A,
          status: "BORRADOR",
          patientName: "Paciente A",
          reason: "Motivo A",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_B,
          status: "BORRADOR",
          patientName: "Paciente B",
          reason: "Motivo B",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_A}/evaluation` && options.method === "PUT") {
        return putAPromise;
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  // 1. Abrir Consulta A y disparar guardado
  await controller.openEvaluation(CONSULTATION_A);
  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  // 2. Abrir Consulta B
  await controller.openEvaluation(CONSULTATION_B);
  assert(!document.querySelector("#evaluation-reason").disabled, "B debe ser editable");
  assert(!document.querySelector("#evaluation-save-button").disabled, "El botón guardar de B debe estar habilitado");

  // 3. Rechazar tardíamente el PUT de A con conflicto 409
  rejectPutA(new ApiRequestError({
    status: 409,
    code: "INVALID_STATUS",
    message: "Consulta A ya no está en estado borrador",
    correlationId: "corr-fail-a"
  }));
  await flushPromises();

  // 4. Verificar que B no quedó bloqueada, ni marcada en conflicto, ni con mensajes de error
  assert(!document.querySelector("#evaluation-reason").disabled, "El formulario de B no debe bloquearse por el fallo de A");
  assert(!document.querySelector("#evaluation-save-button").disabled, "El botón guardar de B debe seguir habilitado");
  assert(document.querySelector("#evaluation-error").hidden, "El error de A no debe mostrarse en la consulta B");
  assert(document.querySelector("#evaluation-validation").hidden, "No debe haber alerta de validación en B");
  equal(document.querySelector("#evaluation-context-status").textContent, "BORRADOR");
  equal(document.querySelector("#evaluation-reason").value, "Motivo B");

  controller.destroy();
});

test("cruce de guardado: respuesta de PUT tardía después de cerrar sesión no restaura datos ni altera controles", async () => {
  let resolvePut;
  const putPromise = new Promise((resolve) => { resolvePut = resolve; });

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          patientName: "Paciente Sesión",
          reason: "Motivo Sesión",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/evaluation` && options.method === "PUT") {
        return putPromise;
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);
  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  // Cerrar sesión mientras el guardado está en vuelo
  controller.sessionEnded();

  assert(document.querySelector("#consultation-evaluation-view").hidden, "La vista debe ocultarse al cerrar sesión");
  equal(document.querySelector("#evaluation-reason").value, "", "Formulario debe estar limpio");
  equal(document.querySelector("#evaluation-context-patient").textContent, "—", "Contexto debe estar reseteado");

  // Resolver PUT tardíamente
  resolvePut({
    id: CONSULTATION_ID,
    status: "BORRADOR",
    patientName: "Paciente Sesión",
    reason: "Motivo guardado tardíamente"
  });
  await flushPromises();

  // Asegurar que nada se restaura en el DOM ni se muestran mensajes
  assert(document.querySelector("#consultation-evaluation-view").hidden, "La vista debe continuar oculta");
  equal(document.querySelector("#evaluation-context-patient").textContent, "—", "No debe restaurar paciente");
  equal(document.querySelector("#evaluation-reason").value, "", "No debe restaurar campos del formulario");
  assert(document.querySelector("#evaluation-notice").hidden, "No debe mostrar aviso de éxito tras cerrar sesión");
  assert(document.querySelector("#evaluation-error").hidden, "No debe mostrar aviso de error tras cerrar sesión");

  controller.destroy();
});

test("cruce de guardado: los datos variables de A nunca se envían al guardar B tras respuesta tardía de A", async () => {
  const CONSULTATION_A = "11111111-1111-4111-8111-111111111111";
  const CONSULTATION_B = "22222222-2222-4222-8222-222222222222";
  let resolvePutA;
  const putAPromise = new Promise((resolve) => { resolvePutA = resolve; });
  let putBCallPayload = null;

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_A}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_A,
          status: "BORRADOR",
          patientName: "Paciente A",
          reason: "Motivo A",
          diagnoses: [],
          clinicalDetails: {
            variableDetails: {
              "claveExclusivaA": "VALOR_SECRETO_A",
              "temperamentoA": "AGRESIVO"
            }
          }
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B}` && (!options.method || options.method === "GET")) {
        return {
          id: CONSULTATION_B,
          status: "BORRADOR",
          patientName: "Paciente B",
          reason: "Motivo B",
          diagnoses: [],
          clinicalDetails: {
            variableDetails: {
              "claveExclusivaB": "VALOR_PROPIO_B"
            }
          }
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_A}/evaluation` && options.method === "PUT") {
        return putAPromise;
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B}/evaluation` && options.method === "PUT") {
        putBCallPayload = JSON.parse(options.body);
        return {
          id: CONSULTATION_B,
          status: "BORRADOR",
          reason: putBCallPayload.reason,
          clinicalDetails: {
            variableDetails: putBCallPayload.variableDetails
          }
        };
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  // 1. Abrir Consulta A e iniciar su guardado
  await controller.openEvaluation(CONSULTATION_A);
  const form = document.querySelector("#consultation-evaluation-form");
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  // 2. Abrir Consulta B
  await controller.openEvaluation(CONSULTATION_B);
  equal(document.querySelector("#evaluation-context-patient").textContent, "Paciente B");

  // 3. Resolver tardíamente el PUT de A con sus variableDetails
  resolvePutA({
    id: CONSULTATION_A,
    status: "BORRADOR",
    reason: "Motivo A Guardado",
    clinicalDetails: {
      variableDetails: {
        "claveExclusivaA": "VALOR_SECRETO_A_ACTUALIZADO",
        "temperamentoA": "AGRESIVO"
      }
    }
  });
  await flushPromises();

  // 4. Modificar y guardar Consulta B
  document.querySelector("#evaluation-reason").value = "Nuevo motivo para B";
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  // 5. Verificar payload enviado para B
  assert(putBCallPayload !== null, "Debe haber enviado PUT para la consulta B");
  equal(putBCallPayload.reason, "Nuevo motivo para B");
  assert(putBCallPayload.variableDetails !== null, "B debe conservar sus variableDetails");
  equal(putBCallPayload.variableDetails["claveExclusivaB"], "VALOR_PROPIO_B");
  assert(putBCallPayload.variableDetails["claveExclusivaA"] === undefined, "Los datos de A nunca deben enviarse en B");
  assert(putBCallPayload.variableDetails["temperamentoA"] === undefined, "Los datos de A nunca deben enviarse en B");

  controller.destroy();
});

test("HU-022: carga de diagnósticos existentes y renderizado accesible de badges (DEFINITIVO, PRESUNTIVO) y código", async () => {
  const calls = [];
  const client = {
    requestJson: async (url) => {
      calls.push(url);
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Consulta general",
          diagnoses: [
            {
              id: "diag-1",
              consultationId: CONSULTATION_ID,
              description: "Gastroenteritis presuntiva",
              diagnosisType: "PRESUNTIVO",
              code: "K52.9",
              notes: "Esperar analítica"
            },
            {
              id: "diag-2",
              consultationId: CONSULTATION_ID,
              description: "Deshidratación grado I",
              diagnosisType: "DEFINITIVO",
              code: null,
              notes: null
            }
          ]
        };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"] });
  await controller.openEvaluation(CONSULTATION_ID);
  await flushPromises();

  equal(document.querySelector("#evaluation-diagnoses-counter").textContent, "2");
  const list = document.querySelector("#evaluation-diagnoses-list");
  assert(!list.hidden, "La lista de diagnósticos debe estar visible");
  assert(document.querySelector("#evaluation-diagnoses-empty").hidden, "Empty-state debe estar oculto");
  equal(list.children.length, 2);

  assert(list.children[0].textContent.includes("PRESUNTIVO"), "Debe mostrar badge PRESUNTIVO");
  assert(list.children[0].textContent.includes("K52.9"), "Debe mostrar código K52.9");
  assert(list.children[0].textContent.includes("Gastroenteritis presuntiva"), "Debe mostrar descripción");
  assert(list.children[0].textContent.includes("Esperar analítica"), "Debe mostrar notas");

  assert(list.children[1].textContent.includes("DEFINITIVO"), "Debe mostrar badge DEFINITIVO");
  assert(list.children[1].textContent.includes("Deshidratación grado I"), "Debe mostrar descripción");

  // Botón añadir diagnóstico debe ser visible en BORRADOR con CONSULTATION_UPDATE
  assert(!document.querySelector("#diagnosis-add-button").hidden, "Botón añadir debe estar visible");

  controller.destroy();
});

test("HU-022: validación en cliente para nuevo diagnóstico: campos requeridos y longitud máxima", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return { id: CONSULTATION_ID, status: "BORRADOR", reason: "Consulta general", diagnoses: [] };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"] });
  await controller.openEvaluation(CONSULTATION_ID);

  // Abrir formulario de diagnóstico
  document.querySelector("#diagnosis-add-button").click();
  assert(!document.querySelector("#diagnosis-form").hidden, "El formulario de diagnóstico debe abrirse");

  // Intento de envío vacío
  document.querySelector("#diagnosis-description").value = "";
  document.querySelector("#diagnosis-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

  assert(!document.querySelector("#diagnosis-validation").hidden, "Debe mostrar alerta de validación");
  equal(document.querySelector("#diagnosis-description").getAttribute("aria-invalid"), "true");

  controller.destroy();
});

test("HU-022: añadir diagnóstico vía POST envía datos correctos, limpia formulario y conserva intactos los datos de evaluación", async () => {
  let postPayload = null;
  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Evaluación en curso no guardada aún",
          anamnesis: "Anamnesis previa intacta",
          diagnoses: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/diagnoses` && options.method === "POST") {
        postPayload = JSON.parse(options.body);
        return {
          id: "diag-new-1",
          consultationId: CONSULTATION_ID,
          description: postPayload.description,
          diagnosisType: postPayload.diagnosisType,
          code: postPayload.code,
          notes: postPayload.notes
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/diagnoses` && (!options.method || options.method === "GET")) {
        return [
          {
            id: "diag-new-1",
            consultationId: CONSULTATION_ID,
            description: "Nuevo diagnóstico clínico",
            diagnosisType: "DEFINITIVO",
            code: "A00.1",
            notes: "Notas del diagnóstico"
          }
        ];
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"] });
  await controller.openEvaluation(CONSULTATION_ID);

  // El usuario escribió algo en el formulario de evaluación que aún no ha guardado
  document.querySelector("#evaluation-reason").value = "Motivo modificado en borrador";
  document.querySelector("#evaluation-temperature").value = "39.1";

  // Abre el formulario de diagnóstico
  document.querySelector("#diagnosis-add-button").click();
  document.querySelector("#diagnosis-description").value = "Nuevo diagnóstico clínico";
  document.querySelector("#diagnosis-type").value = "DEFINITIVO";
  document.querySelector("#diagnosis-code").value = "A00.1";
  document.querySelector("#diagnosis-notes").value = "Notas del diagnóstico";

  document.querySelector("#diagnosis-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(postPayload !== null, "Debe enviar petición POST");
  equal(postPayload.description, "Nuevo diagnóstico clínico");
  equal(postPayload.diagnosisType, "DEFINITIVO");
  equal(postPayload.code, "A00.1");

  // El formulario de diagnóstico debe cerrarse y mostrar mensaje de éxito
  assert(document.querySelector("#diagnosis-form").hidden, "El formulario de diagnóstico debe ocultarse");
  assert(!document.querySelector("#diagnosis-notice").hidden, "Debe mostrar aviso de diagnóstico guardado");

  // CONSERVACIÓN DE LA EVALUACIÓN: los campos de la evaluación permanecen intactos
  equal(document.querySelector("#evaluation-reason").value, "Motivo modificado en borrador", "El motivo de la evaluación no debe borrarse al guardar diagnóstico");
  equal(document.querySelector("#evaluation-temperature").value, "39.1", "La temperatura no debe borrarse");

  // Diagnósticos actualizados en lista
  equal(document.querySelector("#evaluation-diagnoses-counter").textContent, "1");

  controller.destroy();
});

test("HU-022: editar diagnóstico existente vía PUT puebla formulario, envía datos y actualiza lista", async () => {
  let putPayload = null;
  const initialDiags = [
    {
      id: "diag-edit-1",
      consultationId: CONSULTATION_ID,
      description: "Diagnóstico inicial",
      diagnosisType: "PRESUNTIVO",
      code: "B01",
      notes: "Nota inicial"
    }
  ];

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return { id: CONSULTATION_ID, status: "BORRADOR", reason: "Consulta", diagnoses: initialDiags };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/diagnoses/diag-edit-1` && options.method === "PUT") {
        putPayload = JSON.parse(options.body);
        return {
          id: "diag-edit-1",
          consultationId: CONSULTATION_ID,
          description: putPayload.description,
          diagnosisType: putPayload.diagnosisType,
          code: putPayload.code,
          notes: putPayload.notes
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/diagnoses` && (!options.method || options.method === "GET")) {
        return [
          {
            id: "diag-edit-1",
            consultationId: CONSULTATION_ID,
            description: "Diagnóstico modificado confirmado",
            diagnosisType: "DEFINITIVO",
            code: "B02",
            notes: "Nota actualizada"
          }
        ];
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"] });
  await controller.openEvaluation(CONSULTATION_ID);

  // Clic en editar en el item de la lista
  const editBtn = document.querySelector("button[data-action='edit-diagnosis']");
  assert(editBtn !== null, "Debe existir botón Editar diagnóstico");
  editBtn.click();

  assert(!document.querySelector("#diagnosis-form").hidden, "El formulario debe abrirse");
  equal(document.querySelector("#diagnosis-form-title").textContent, "Editar diagnóstico");
  equal(document.querySelector("#diagnosis-description").value, "Diagnóstico inicial");
  equal(document.querySelector("#diagnosis-type").value, "PRESUNTIVO");
  equal(document.querySelector("#diagnosis-code").value, "B01");

  // Modificar
  document.querySelector("#diagnosis-description").value = "Diagnóstico modificado confirmado";
  document.querySelector("#diagnosis-type").value = "DEFINITIVO";
  document.querySelector("#diagnosis-code").value = "B02";
  document.querySelector("#diagnosis-notes").value = "Nota actualizada";

  document.querySelector("#diagnosis-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(putPayload !== null, "Debe haber enviado PUT");
  equal(putPayload.description, "Diagnóstico modificado confirmado");
  equal(putPayload.diagnosisType, "DEFINITIVO");
  equal(putPayload.code, "B02");

  assert(document.querySelector("#diagnosis-form").hidden, "El formulario debe ocultarse tras edición");
  assert(!document.querySelector("#diagnosis-notice").hidden, "Aviso de éxito visible");

  controller.destroy();
});

test("HU-022: modo de solo lectura para diagnósticos si la consulta no es BORRADOR o falta CONSULTATION_UPDATE", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "FINALIZADA",
          reason: "Consulta cerrada",
          diagnoses: [
            {
              id: "diag-fin-1",
              consultationId: CONSULTATION_ID,
              description: "Diagnóstico finalizado",
              diagnosisType: "DEFINITIVO",
              code: "K01",
              notes: null
            }
          ]
        };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"] });
  await controller.openEvaluation(CONSULTATION_ID);

  assert(document.querySelector("#diagnosis-add-button").hidden, "Botón añadir debe estar oculto en FINALIZADA");
  assert(document.querySelector("button[data-action='edit-diagnosis']") === null, "No debe renderizar botones de edición en FINALIZADA");

  controller.destroy();
});

test("HU-022: fallo al cargar diagnósticos distingue error de lista vacía, muestra error accesible con correlation ID y permite reintentar", async () => {
  let failDiagnoses = true;
  let retryCount = 0;

  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          status: "BORRADOR",
          reason: "Evaluación clínica",
          diagnoses: null // Fuerza llamada a GET /diagnoses
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/diagnoses`) {
        if (failDiagnoses) {
          throw new ApiRequestError({
            status: 500,
            kind: "HTTP_ERROR",
            code: "SERVER_ERROR",
            message: "Fallo temporal de base de datos",
            correlationId: "CID-EVAL-DIAG-500"
          });
        }
        retryCount++;
        return []; // Retorna lista vacía en el reintento
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"] });
  await controller.openEvaluation(CONSULTATION_ID);

  const errorBox = document.querySelector("#evaluation-diagnoses-load-error");
  const emptyState = document.querySelector("#evaluation-diagnoses-empty");
  const list = document.querySelector("#evaluation-diagnoses-list");

  // No debe mostrar "sin diagnósticos" si falló
  assert(emptyState.hidden, "No debe mostrar 'sin diagnósticos' cuando falló la carga");
  assert(!errorBox.hidden, "Debe mostrar el contenedor de error accesible");
  equal(errorBox.getAttribute("role"), "alert");
  assert(document.querySelector("#evaluation-diagnoses-load-error-message").textContent.includes("Fallo temporal"), "Debe mostrar mensaje de error");
  assert(document.querySelector("#evaluation-diagnoses-load-error-correlation").textContent.includes("CID-EVAL-DIAG-500"), "Debe mostrar correlation ID");

  // Ejecutar reintento
  failDiagnoses = false;
  document.querySelector("#evaluation-diagnoses-retry-button").click();
  await flushPromises();

  equal(retryCount, 1, "Debe ejecutar nueva llamada en reintento");
  assert(errorBox.hidden, "Error debe ocultarse tras reintento exitoso");
  assert(!emptyState.hidden, "Debe mostrar 'sin diagnósticos' tras respuesta vacía válida");
  assert(list.hidden, "La lista debe permanecer oculta con 0 diagnósticos");

  controller.destroy();
});

test("HU-023: renderizado de prescripciones emitidas, badges y estructura de ítems", async () => {
  const prescData = [
    {
      id: "99999999-aaaa-4999-8999-999999999999",
      consultationId: CONSULTATION_ID,
      veterinarianId: VET_ID,
      veterinarianName: "Dra. Sofía Morales",
      status: "EMITIDA",
      generalInstructions: "Administrar con abundante agua.",
      idempotencyKey: "test-key-1",
      issuedAt: "2026-10-25T12:00:00Z",
      items: [
        {
          id: "88888888-bbbb-4888-8888-888888888888",
          medicationName: "Amoxicilina 500mg",
          dosage: "1 comprimido",
          frequency: "Cada 12 horas",
          duration: "7 días",
          route: "Oral",
          instructions: "Con alimento"
        }
      ]
    }
  ];

  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta médica general",
          diagnoses: [],
          prescriptions: prescData
        };
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const counter = document.querySelector("#evaluation-prescriptions-counter");
  const empty = document.querySelector("#evaluation-prescriptions-empty");
  const list = document.querySelector("#evaluation-prescriptions-list");

  equal(counter.textContent, "1");
  assert(empty.hidden, "El estado vacío debe estar oculto con 1 prescripción");
  assert(!list.hidden, "La lista de prescripciones debe estar visible");

  const cards = list.querySelectorAll(".pc-prescription-card");
  equal(cards.length, 1);
  const card = cards[0];
  equal(card.dataset.prescriptionId, "99999999-aaaa-4999-8999-999999999999");
  assert(card.textContent.includes("EMITIDA"), "Debe mostrar badge EMITIDA");
  assert(card.textContent.includes("Amoxicilina 500mg"), "Debe listar el medicamento");
  assert(card.textContent.includes("Administrar con abundante agua."), "Debe mostrar indicaciones generales");
  assert(card.textContent.includes("Medicamentos externos al catálogo"), "Debe indicar medicamentos externos");

  controller.destroy();
});

test("HU-023: dinámica de filas de medicamentos (añadir fila, deshabilitar eliminación en fila única y reindexar)", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta general",
          diagnoses: [],
          prescriptions: []
        };
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const addBtn = document.querySelector("#prescription-add-button");
  const form = document.querySelector("#prescription-form");
  const itemsContainer = document.querySelector("#prescription-items-container");
  const addRowBtn = document.querySelector("#prescription-add-item-row-button");

  assert(!addBtn.hidden, "Botón de emitir prescripción debe ser visible para vet asignado en BORRADOR");
  addBtn.click();

  assert(!form.hidden, "Formulario de prescripción debe mostrarse al hacer clic en + Emitir");
  let rows = itemsContainer.querySelectorAll(".pc-prescription-item-row");
  equal(rows.length, 1, "Debe iniciar con 1 fila de medicamento");
  equal(rows[0].querySelector(".pc-prescription-item-badge").textContent, "Medicamento #1");
  assert(rows[0].querySelector(".pc-prescription-item-remove-btn").disabled, "Eliminar debe estar deshabilitado con 1 sola fila");

  // Añadir segunda fila
  addRowBtn.click();
  rows = itemsContainer.querySelectorAll(".pc-prescription-item-row");
  equal(rows.length, 2, "Debe tener 2 filas tras añadir una");
  equal(rows[1].querySelector(".pc-prescription-item-badge").textContent, "Medicamento #2");
  assert(!rows[0].querySelector(".pc-prescription-item-remove-btn").disabled, "Fila 1 debe habilitar eliminar con 2 filas");
  assert(!rows[1].querySelector(".pc-prescription-item-remove-btn").disabled, "Fila 2 debe habilitar eliminar con 2 filas");

  // Añadir tercera fila y eliminar la del medio
  addRowBtn.click();
  rows = itemsContainer.querySelectorAll(".pc-prescription-item-row");
  equal(rows.length, 3, "Debe tener 3 filas");
  equal(rows[2].querySelector(".pc-prescription-item-badge").textContent, "Medicamento #3");

  rows[1].querySelector(".pc-prescription-item-remove-btn").click();
  rows = itemsContainer.querySelectorAll(".pc-prescription-item-row");
  equal(rows.length, 2, "Debe quedar con 2 filas");
  equal(rows[0].querySelector(".pc-prescription-item-badge").textContent, "Medicamento #1");
  equal(rows[1].querySelector(".pc-prescription-item-badge").textContent, "Medicamento #2");

  controller.destroy();
});

test("HU-023: validación en cliente detecta campos obligatorios faltantes o longitudes excedidas", async () => {
  let postCalled = false;
  const client = {
    requestJson: async (url, opts) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (opts?.method === "POST") {
        postCalled = true;
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  document.querySelector("#prescription-add-button").click();
  const form = document.querySelector("#prescription-form");
  const validationAlert = document.querySelector("#prescription-validation");

  // Enviar con campos vacíos
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(!postCalled, "No debe enviar POST con campos vacíos");
  assert(!validationAlert.hidden, "Debe mostrar alerta de validación");
  const row = document.querySelector("#prescription-items-container .pc-prescription-item-row");
  equal(row.querySelector(".pc-prescription-item-name").getAttribute("aria-invalid"), "true");
  equal(row.querySelector(".pc-prescription-item-dosage").getAttribute("aria-invalid"), "true");

  controller.destroy();
});

test("HU-023: emisión exitosa vía POST envía datos correctos, limpia formulario y actualiza lista", async () => {
  const requests = [];
  const client = {
    requestJson: async (url, opts = {}) => {
      requests.push({ url, method: opts.method || "GET", body: opts.body ? JSON.parse(opts.body) : null });
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && opts.method === "POST") {
        return {
          id: "77777777-cccc-4777-8777-777777777777",
          consultationId: CONSULTATION_ID,
          veterinarianId: VET_ID,
          veterinarianName: "Dra. Sofía Morales",
          status: "EMITIDA",
          generalInstructions: "Tomar con comida.",
          idempotencyKey: opts.body ? JSON.parse(opts.body).idempotencyKey : "key-1",
          issuedAt: "2026-10-25T14:00:00Z",
          items: [
            {
              id: "66666666-dddd-4666-8666-666666666666",
              medicationName: "Meloxicam 1mg",
              dosage: "1 tableta",
              frequency: "Cada 24 horas",
              duration: "3 días",
              route: "Oral",
              instructions: "Junto al alimento matutino"
            }
          ]
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && (!opts.method || opts.method === "GET")) {
        return [
          {
            id: "77777777-cccc-4777-8777-777777777777",
            consultationId: CONSULTATION_ID,
            veterinarianId: VET_ID,
            veterinarianName: "Dra. Sofía Morales",
            status: "EMITIDA",
            generalInstructions: "Tomar con comida.",
            idempotencyKey: "key-1",
            issuedAt: "2026-10-25T14:00:00Z",
            items: [
              {
                id: "66666666-dddd-4666-8666-666666666666",
                medicationName: "Meloxicam 1mg",
                dosage: "1 tableta",
                frequency: "Cada 24 horas",
                duration: "3 días",
                route: "Oral",
                instructions: "Junto al alimento matutino"
              }
            ]
          }
        ];
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  document.querySelector("#prescription-add-button").click();
  const row = document.querySelector("#prescription-items-container .pc-prescription-item-row");
  row.querySelector(".pc-prescription-item-name").value = "Meloxicam 1mg";
  row.querySelector(".pc-prescription-item-dosage").value = "1 tableta";
  row.querySelector(".pc-prescription-item-frequency").value = "Cada 24 horas";
  row.querySelector(".pc-prescription-item-duration").value = "3 días";
  row.querySelector(".pc-prescription-item-route").value = "Oral";
  row.querySelector(".pc-prescription-item-instructions").value = "Junto al alimento matutino";
  document.querySelector("#prescription-general-instructions").value = "Tomar con comida.";

  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  const postReq = requests.find((r) => r.method === "POST");
  assert(Boolean(postReq), "Debe enviar petición POST");
  equal(postReq.url, `/api/v1/consultations/${CONSULTATION_ID}/prescriptions`);
  equal(postReq.body.generalInstructions, "Tomar con comida.");
  assert(Boolean(postReq.body.idempotencyKey), "Debe incluir idempotencyKey");
  equal(postReq.body.items.length, 1);
  equal(postReq.body.items[0].medicationName, "Meloxicam 1mg");

  // Formulario debe cerrarse
  assert(document.querySelector("#prescription-form").hidden, "Formulario debe ocultarse tras emisión exitosa");
  // Alerta de éxito visible
  assert(!document.querySelector("#prescription-notice").hidden, "Debe mostrar aviso de éxito");
  // Lista actualizada
  equal(document.querySelector("#evaluation-prescriptions-counter").textContent, "1");
  const cards = document.querySelectorAll("#evaluation-prescriptions-list .pc-prescription-card");
  equal(cards.length, 1);

  controller.destroy();
});

test("HU-023: reintento tras error de red conserva los valores y el mismo idempotencyKey", async () => {
  const postBodies = [];
  let failFirstPost = true;

  const client = {
    requestJson: async (url, opts = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && opts.method === "POST") {
        const body = JSON.parse(opts.body);
        postBodies.push(body);
        if (failFirstPost) {
          failFirstPost = false;
          throw new ApiRequestError({
            kind: "HTTP_500",
            status: 500,
            message: "Fallo temporal de conexión",
            correlationId: "CID-PRESCR-500"
          });
        }
        return {
          id: "77777777-cccc-4777-8777-777777777777",
          consultationId: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "EMITIDA",
          items: body.items,
          issuedAt: "2026-10-25T14:00:00Z"
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && (!opts.method || opts.method === "GET")) {
        return [];
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  document.querySelector("#prescription-add-button").click();
  const row = document.querySelector("#prescription-items-container .pc-prescription-item-row");
  row.querySelector(".pc-prescription-item-name").value = "Ketoprofeno 10mg";
  row.querySelector(".pc-prescription-item-dosage").value = "1 gota/kg";
  row.querySelector(".pc-prescription-item-frequency").value = "Cada 24h";
  row.querySelector(".pc-prescription-item-duration").value = "2 días";

  // Primer intento: falla
  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  equal(postBodies.length, 1);
  assert(!document.querySelector("#prescription-error").hidden, "Debe mostrar alerta de error");
  assert(!document.querySelector("#prescription-form").hidden, "El formulario debe permanecer abierto con los datos");
  equal(row.querySelector(".pc-prescription-item-name").value, "Ketoprofeno 10mg");

  // Segundo intento (reintento del usuario)
  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  equal(postBodies.length, 2);
  equal(postBodies[0].idempotencyKey, postBodies[1].idempotencyKey, "Ambas solicitudes deben compartir el mismo idempotencyKey");

  controller.destroy();
});

test("HU-023: oculta botón de emitir si la consulta no es BORRADOR o el usuario no es el vet asignado", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: "different-vet-id",
          status: "BORRADOR",
          reason: "Consulta",
          diagnoses: [],
          prescriptions: []
        };
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const addBtn = document.querySelector("#prescription-add-button");
  assert(addBtn.hidden, "Botón de emitir debe estar oculto si el usuario no es el veterinario asignado");

  controller.destroy();
});

test("HU-023: fallo al cargar prescripciones distingue error de lista vacía y permite reintentar", async () => {
  let failPrescriptions = true;
  let retryCount = 0;

  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta",
          diagnoses: [],
          prescriptions: null // Fuerza llamada a GET /prescriptions
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions`) {
        if (failPrescriptions) {
          throw new ApiRequestError({
            kind: "HTTP_500",
            status: 500,
            message: "Fallo temporal de prescripciones",
            correlationId: "CID-PRESC-500"
          });
        }
        retryCount++;
        return [];
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const emptyState = document.querySelector("#evaluation-prescriptions-empty");
  const errorBox = document.querySelector("#evaluation-prescriptions-load-error");

  assert(emptyState.hidden, "No debe mostrar 'sin prescripciones' cuando falló la consulta");
  assert(!errorBox.hidden, "Debe mostrar el contenedor de error accesible");
  assert(document.querySelector("#evaluation-prescriptions-load-error-message").textContent.includes("Fallo temporal"), "Debe mostrar mensaje");
  assert(document.querySelector("#evaluation-prescriptions-load-error-correlation").textContent.includes("CID-PRESC-500"), "Debe mostrar correlation ID");

  // Reintento
  failPrescriptions = false;
  document.querySelector("#evaluation-prescriptions-retry-button").click();
  await flushPromises();

  equal(retryCount, 1, "Debe ejecutar nueva llamada en reintento");
  assert(errorBox.hidden, "Error debe ocultarse tras reintento exitoso");
  assert(!emptyState.hidden, "Debe mostrar 'sin prescripciones' tras respuesta vacía válida");

  controller.destroy();
});

test("HU-023: respuesta perdida y edición posterior conserva el intento y la misma clave para no duplicar automáticamente", async () => {
  const postBodies = [];
  let failFirstPost = true;

  const client = {
    requestJson: async (url, opts = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta general",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && opts.method === "POST") {
        const body = JSON.parse(opts.body);
        postBodies.push(body);
        if (failFirstPost) {
          failFirstPost = false;
          throw new ApiRequestError({
            kind: "HTTP_504",
            status: 504,
            message: "Tiempo de espera agotado al conectar con el servidor",
            correlationId: "CID-TIMEOUT-504"
          });
        }
        throw new ApiRequestError({
          kind: "HTTP_409",
          status: 409,
          code: "CONFLICT",
          message: "La clave de idempotencia ya fue utilizada con un contenido diferente.",
          correlationId: "CID-IDEMP-CONFLICT-409"
        });
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && (!opts.method || opts.method === "GET")) {
        return [];
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  document.querySelector("#prescription-add-button").click();
  const row = document.querySelector("#prescription-items-container .pc-prescription-item-row");
  row.querySelector(".pc-prescription-item-name").value = "Meloxicam 0.5%";
  row.querySelector(".pc-prescription-item-dosage").value = "0.1 mg/kg";
  row.querySelector(".pc-prescription-item-frequency").value = "Cada 24h";
  row.querySelector(".pc-prescription-item-duration").value = "3 días";

  // 1. Envío inicial que sufre respuesta perdida / timeout
  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  equal(postBodies.length, 1);
  assert(!document.querySelector("#prescription-error").hidden, "Debe mostrar alerta de error");

  // 2. El usuario edita la dosis en el formulario sin cancelar
  row.querySelector(".pc-prescription-item-dosage").value = "0.2 mg/kg";

  // 3. Envío posterior
  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  equal(postBodies.length, 2);
  equal(postBodies[0].idempotencyKey, postBodies[1].idempotencyKey, "No debe generar una clave nueva automáticamente al editar tras respuesta perdida");
  equal(postBodies[1].items[0].dosage, "0.2 mg/kg", "Envía la dosis actualizada");

  // 4. Ante 409 CONFLICT por idempotencia:
  assert(!document.querySelector("#prescription-error").hidden, "Muestra error de conflicto");
  assert(document.querySelector("#prescription-error-message").textContent.includes("contenido diferente"));
  assert(!document.querySelector("#evaluation-reason").disabled, "El formulario de evaluación NO debe bloquearse por conflicto de idempotencia");
  assert(!document.querySelector("#evaluation-save-button").disabled, "El botón de guardar consulta debe seguir habilitado");

  controller.destroy();
});

test("HU-023: error 409 INVALID_CONSULTATION_STATUS bloquea evaluación general pero 409 CONFLICT no lo hace", async () => {
  let conflictType = "CONFLICT";

  const client = {
    requestJson: async (url, opts = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta general",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && opts.method === "POST") {
        if (conflictType === "INVALID_CONSULTATION_STATUS") {
          throw new ApiRequestError({
            kind: "HTTP_409",
            status: 409,
            code: "INVALID_CONSULTATION_STATUS",
            message: "La consulta ya no se encuentra en estado BORRADOR."
          });
        }
        throw new ApiRequestError({
          kind: "HTTP_409",
          status: 409,
          code: "CONFLICT",
          message: "Conflicto de clave de idempotencia."
        });
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/prescriptions` && (!opts.method || opts.method === "GET")) {
        return [];
      }
      throw new Error(`URL no manejada: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  // Escenario A: 409 CONFLICT (idempotencia)
  conflictType = "CONFLICT";
  document.querySelector("#prescription-add-button").click();
  const row = document.querySelector("#prescription-items-container .pc-prescription-item-row");
  row.querySelector(".pc-prescription-item-name").value = "Cefalexina 500mg";
  row.querySelector(".pc-prescription-item-dosage").value = "1 tableta";
  row.querySelector(".pc-prescription-item-frequency").value = "Cada 12h";
  row.querySelector(".pc-prescription-item-duration").value = "7 días";

  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(!document.querySelector("#evaluation-reason").disabled, "409 CONFLICT no debe bloquear evaluación general");
  assert(!document.querySelector("#evaluation-save-button").disabled);

  // Escenario B: 409 INVALID_CONSULTATION_STATUS
  conflictType = "INVALID_CONSULTATION_STATUS";
  document.querySelector("#prescription-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await flushPromises();

  assert(document.querySelector("#evaluation-reason").disabled, "409 INVALID_CONSULTATION_STATUS debe bloquear evaluación general");
  assert(document.querySelector("#evaluation-save-button").disabled);

  controller.destroy();
});

test("HU-024: botón 'Finalizar consulta' solo es visible y editable para veterinario asignado con CONSULTATION_FINALIZE en BORRADOR", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta general",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  // Escenario 1: Sin CONSULTATION_FINALIZE
  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Sin CONSULTATION_FINALIZE el botón debe estar oculto");

  // Escenario 2: Otro veterinario asignado
  controller.sessionReady({
    userId: "other-vet-id",
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Veterinario no asignado no debe ver el botón de finalizar");

  // Escenario 3: Veterinario asignado con CONSULTATION_FINALIZE
  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);
  assert(!document.querySelector("#evaluation-finalize-button").hidden, "Veterinario asignado y autorizado debe ver el botón de finalizar");
  assert(!document.querySelector("#evaluation-finalize-button").disabled, "El botón no debe estar deshabilitado");

  controller.destroy();
});

test("HU-024: bloquea finalización si existen cambios pendientes en evaluación, diagnóstico o prescripción", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Motivo original guardado",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);

  const finalizeBtn = document.querySelector("#evaluation-finalize-button");
  const dialog = document.querySelector("#evaluation-finalize-dialog");
  const validationAlert = document.querySelector("#evaluation-validation");

  // Caso A: Modificación en evaluación
  document.querySelector("#evaluation-reason").value = "Motivo modificado no guardado";
  finalizeBtn.click();
  await flushPromises();

  assert(dialog.hidden || !dialog.hasAttribute("open"), "No debe abrir diálogo con cambios pendientes en evaluación");
  assert(!validationAlert.hidden, "Debe mostrar alerta de validación advirtiendo cambios pendientes");
  assert(validationAlert.textContent.includes("sin guardar"), "Alerta debe indicar cambios sin guardar");

  // Restaurar valor para limpiar estado sucio de evaluación
  document.querySelector("#evaluation-reason").value = "Motivo original guardado";

  // Caso B: Formulario de diagnóstico abierto
  document.querySelector("#diagnosis-add-button").click();
  finalizeBtn.click();
  await flushPromises();

  assert(dialog.hidden || !dialog.hasAttribute("open"), "No debe abrir diálogo con formulario de diagnóstico abierto");
  assert(!validationAlert.hidden, "Debe advertir cambios pendientes cuando formulario diagnóstico está abierto");

  // Cerrar formulario de diagnóstico
  document.querySelector("#diagnosis-cancel-button").click();

  // Caso C: Formulario de prescripción abierto
  document.querySelector("#prescription-add-button").click();
  finalizeBtn.click();
  await flushPromises();

  assert(dialog.hidden || !dialog.hasAttribute("open"), "No debe abrir diálogo con formulario de prescripción abierto");
  assert(!validationAlert.hidden, "Debe advertir cambios pendientes cuando formulario de receta está abierto");

  document.querySelector("#prescription-cancel-button").click();

  controller.destroy();
});

test("HU-024: finaliza consulta atómicamente, pasa a solo lectura y actualiza estado visual a FINALIZADA", async () => {
  let finalizeCalled = false;
  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Motivo completo",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/finalize`) {
        finalizeCalled = true;
        assert(options.method === "POST", "Debe ser método POST");
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "FINALIZADA",
          reason: "Motivo completo",
          finalizedAt: "2026-10-25T17:00:00Z",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);

  const finalizeBtn = document.querySelector("#evaluation-finalize-button");
  const dialog = document.querySelector("#evaluation-finalize-dialog");
  const confirmBtn = document.querySelector("#evaluation-finalize-confirm-button");
  const cancelBtn = document.querySelector("#evaluation-finalize-cancel-button");

  // Abrir diálogo
  finalizeBtn.click();
  await flushPromises();
  assert(!dialog.hidden || dialog.hasAttribute("open"), "Debe abrir diálogo de confirmación");

  // Probar botón cancelar
  cancelBtn.click();
  await flushPromises();
  assert(dialog.hidden || !dialog.hasAttribute("open"), "Debe cerrar diálogo al cancelar");

  // Volver a abrir y confirmar
  finalizeBtn.click();
  await flushPromises();
  confirmBtn.click();
  await flushPromises();

  assert(finalizeCalled, "Debe llamar al endpoint de finalización");
  assert(dialog.hidden || !dialog.hasAttribute("open"), "Diálogo debe cerrarse tras finalizar");

  // Validar estado de solo lectura y badge
  assert(document.querySelector("#evaluation-reason").disabled, "Campos de evaluación deben quedar deshabilitados");
  assert(document.querySelector("#evaluation-save-button").hidden, "Botón Guardar debe ocultarse");
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Botón Finalizar debe ocultarse");
  assert(document.querySelector("#diagnosis-add-button").hidden, "Botón Añadir diagnóstico debe ocultarse");
  assert(document.querySelector("#prescription-add-button").hidden, "Botón Añadir prescripción debe ocultarse");

  const statusBadge = document.querySelector("#evaluation-context-status");
  equal(statusBadge.textContent.trim(), "FINALIZADA");
  assert(statusBadge.classList.contains("bg-success"), "Badge debe tener clase bg-success");

  const notice = document.querySelector("#evaluation-notice");
  assert(!notice.hidden, "Debe mostrar aviso de consulta finalizada");
  assert(notice.textContent.includes("solo lectura"), "Aviso debe indicar modo de solo lectura");

  controller.destroy();
});

test("HU-024: consulta abierta inicialmente en FINALIZADA carga en modo de solo lectura", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "FINALIZADA",
          reason: "Motivo consulta previa",
          finalizedAt: "2026-10-25T16:00:00Z",
          diagnoses: [
            {
              id: "diag-1",
              diagnosisType: "DEFINITIVO",
              description: "Otitis externa bilateral"
            }
          ],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);

  assert(document.querySelector("#evaluation-reason").disabled, "Evaluación debe estar deshabilitada");
  assert(document.querySelector("#evaluation-save-button").hidden, "Guardar debe estar oculto");
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Finalizar debe estar oculto");
  assert(document.querySelector("#diagnosis-add-button").hidden, "Añadir diagnóstico debe estar oculto");
  assert(document.querySelector("#prescription-add-button").hidden, "Añadir prescripción debe oculto");

  const diagEditBtn = document.querySelector('[data-action="edit-diagnosis"]');
  assert(diagEditBtn === null, "Diagnósticos no deben tener botón de edición en modo lectura");

  const statusBadge = document.querySelector("#evaluation-context-status");
  equal(statusBadge.textContent.trim(), "FINALIZADA");

  controller.destroy();
});

test("HU-024: edición durante PUT pendiente conserva cambios posteriores y bloquea finalización al resolver", async () => {
  let resolvePutPromise;
  const putPromise = new Promise((resolve) => {
    resolvePutPromise = resolve;
  });

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Motivo inicial persistido",
          anamnesis: "Anamnesis inicial",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/evaluation` && options.method === "PUT") {
        await putPromise;
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Motivo modificado enviado",
          anamnesis: "Anamnesis inicial",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });
  await controller.openEvaluation(CONSULTATION_ID);

  const reasonInput = document.querySelector("#evaluation-reason");
  const anamnesisInput = document.querySelector("#evaluation-anamnesis");
  const finalizeBtn = document.querySelector("#evaluation-finalize-button");
  const form = document.querySelector("#consultation-evaluation-form");

  // Usuario modifica el motivo y guarda
  reasonInput.value = "Motivo modificado enviado";
  reasonInput.dispatchEvent(new Event("input"));
  form.dispatchEvent(new Event("submit"));

  // Mientras el PUT está pendiente en red, el usuario escribe en anamnesis
  anamnesisInput.value = "Nueva anamnesis ingresada mientras se guardaba";
  anamnesisInput.dispatchEvent(new Event("input"));

  assert(finalizeBtn.disabled, "Botón finalizar debe estar deshabilitado mientras se guarda o hay cambios pendientes");

  // Se resuelve el guardado en el servidor
  resolvePutPromise();
  await flushPromises();

  // El texto modificado por el usuario durante la petición DEBE conservarse
  equal(anamnesisInput.value, "Nueva anamnesis ingresada mientras se guardaba");
  // Los cambios posteriores siguen pendientes y bloquean la finalización
  assert(finalizeBtn.disabled, "Botón finalizar debe continuar deshabilitado por cambios pendientes no guardados");

  // Incluso si se intenta forzar la apertura del diálogo, handleOpenFinalizeDialog valida y bloquea
  finalizeBtn.disabled = false;
  finalizeBtn.click();
  await flushPromises();
  const dialog = document.querySelector("#evaluation-finalize-dialog");
  assert(dialog.hidden || !dialog.hasAttribute("open"), "Diálogo no debe abrirse si hay cambios pendientes");
  const validationAlert = document.querySelector("#evaluation-validation");
  assert(!validationAlert.hidden, "Debe mostrar alerta de cambios pendientes");

  controller.destroy();
});

test("HU-024: finalizar consulta A y abrir B antes de recibir respuesta descarta respuesta tardía de A", async () => {
  const CONSULTATION_B_ID = "99999999-bbbb-4999-8999-999999999999";
  let resolveFinalizeA;
  const finalizeAPromise = new Promise((resolve) => {
    resolveFinalizeA = resolve;
  });

  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta A",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_B_ID}`) {
        return {
          id: CONSULTATION_B_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta B",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/finalize`) {
        await finalizeAPromise;
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "FINALIZADA",
          reason: "Consulta A",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });

  // Abrir consulta A
  await controller.openEvaluation(CONSULTATION_ID);
  equal(document.querySelector("#evaluation-reason").value, "Consulta A");

  // Iniciar finalización de consulta A
  const finalizeBtn = document.querySelector("#evaluation-finalize-button");
  const confirmBtn = document.querySelector("#evaluation-finalize-confirm-button");
  finalizeBtn.click();
  await flushPromises();
  confirmBtn.click();
  await flushPromises();

  // Antes de que responda A, se abre la consulta B
  await controller.openEvaluation(CONSULTATION_B_ID);
  equal(document.querySelector("#evaluation-reason").value, "Consulta B");
  equal(document.querySelector("#evaluation-context-status").textContent.trim(), "BORRADOR");
  assert(!document.querySelector("#evaluation-reason").disabled, "Consulta B debe estar en modo editable");

  // Ahora responde la finalización tardía de A
  resolveFinalizeA();
  await flushPromises();

  // Consulta B no debe haber sido afectada ni mutada a FINALIZADA
  equal(document.querySelector("#evaluation-reason").value, "Consulta B");
  equal(document.querySelector("#evaluation-context-status").textContent.trim(), "BORRADOR");
  assert(!document.querySelector("#evaluation-reason").disabled, "Consulta B debe continuar editable");
  assert(!document.querySelector("#evaluation-save-button").hidden, "Botón Guardar en B debe seguir visible");

  controller.destroy();
});

test("HU-024: respuesta de finalización recibida tras logout se descarta limpiamente", async () => {
  let resolveFinalize;
  const finalizePromise = new Promise((resolve) => {
    resolveFinalize = resolve;
  });

  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta en curso",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/finalize`) {
        await finalizePromise;
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "FINALIZADA",
          reason: "Consulta en curso",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  // Iniciar finalización
  document.querySelector("#evaluation-finalize-button").click();
  await flushPromises();
  document.querySelector("#evaluation-finalize-confirm-button").click();
  await flushPromises();

  // Se cierra sesión antes de recibir la respuesta
  controller.sessionEnded();

  // Responde el backend
  resolveFinalize();
  await flushPromises();

  // No debe fallar ni alterar la vista tras el cierre de sesión
  assert(document.querySelector("#evaluation-notice").hidden, "Aviso de finalización no debe mostrarse tras cierre de sesión");
  const dialog = document.querySelector("#evaluation-finalize-dialog");
  assert(dialog.hidden || !dialog.hasAttribute("open"), "Diálogo debe permanecer cerrado");

  controller.destroy();
});

test("HU-024: fallo de finalización y reintento exitoso", async () => {
  let attempts = 0;
  const client = {
    requestJson: async (url, options = {}) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Consulta con fallo transitorio",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/finalize`) {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiRequestError({
            message: "Error temporal del servidor al finalizar",
            status: 500,
            correlationId: "corr-err-500"
          });
        }
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "FINALIZADA",
          reason: "Consulta con fallo transitorio",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const finalizeBtn = document.querySelector("#evaluation-finalize-button");
  const confirmBtn = document.querySelector("#evaluation-finalize-confirm-button");
  const dialog = document.querySelector("#evaluation-finalize-dialog");
  const dialogError = document.querySelector("#evaluation-finalize-dialog-error");
  const dialogErrorMsg = document.querySelector("#evaluation-finalize-dialog-error-message");

  // Abrir diálogo y primer intento (falla)
  finalizeBtn.click();
  await flushPromises();
  confirmBtn.click();
  await flushPromises();

  equal(attempts, 1);
  assert(!dialog.hidden && (dialog.open || dialog.hasAttribute("open")), "Diálogo debe permanecer abierto tras error");
  assert(!dialogError.hidden, "Debe mostrar contenedor de error en el diálogo");
  assert(dialogErrorMsg.textContent.includes("Error temporal del servidor al finalizar"), "Debe mostrar mensaje de error");
  assert(dialogErrorMsg.textContent.includes("corr-err-500"), "Debe incluir ID de correlación");
  assert(!confirmBtn.disabled, "Botón de confirmación debe rehabilitarse para permitir reintento");

  // Segundo intento: reintento exitoso
  confirmBtn.click();
  await flushPromises();

  equal(attempts, 2);
  assert(dialog.hidden || !dialog.hasAttribute("open"), "Diálogo debe cerrarse tras reintento exitoso");
  equal(document.querySelector("#evaluation-context-status").textContent.trim(), "FINALIZADA");
  assert(document.querySelector("#evaluation-reason").disabled, "Campos deben quedar en solo lectura");

  controller.destroy();
});

test("HU-024: cancelación nativa del diálogo (evento cancel / tecla Esc): cancelable en reposo, evitada en curso", async () => {
  let resolveFinalize;
  const finalizePromise = new Promise((resolve) => {
    resolveFinalize = resolve;
  });

  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "BORRADOR",
          reason: "Prueba cancelación diálogo",
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/finalize`) {
        await finalizePromise;
        return {
          id: CONSULTATION_ID,
          veterinarianId: VET_ID,
          status: "FINALIZADA",
          reason: "Prueba cancelación diálogo",
          diagnoses: [],
          prescriptions: []
        };
      }
      return {};
    }
  };

  const controller = createConsultationEvaluationController({
    document,
    client,
    onBackToAppointments: () => {}
  });

  controller.sessionReady({
    userId: VET_ID,
    authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"]
  });

  await controller.openEvaluation(CONSULTATION_ID);

  const finalizeBtn = document.querySelector("#evaluation-finalize-button");
  const dialog = document.querySelector("#evaluation-finalize-dialog");

  // 1. En reposo: evento cancel nativo cierra el diálogo sin preventDefault
  finalizeBtn.click();
  await flushPromises();
  assert(!dialog.hidden && (dialog.open || dialog.hasAttribute("open")), "Diálogo debe estar abierto");

  const cancelEvt1 = new Event("cancel", { cancelable: true });
  dialog.dispatchEvent(cancelEvt1);
  assert(!cancelEvt1.defaultPrevented, "Evento cancel no debe ser prevenido cuando está en reposo");
  assert(dialog.hidden || !dialog.hasAttribute("open"), "Diálogo debe cerrarse");

  // 2. En curso: mientras se está finalizando, el evento cancel nativo debe ser prevenido
  finalizeBtn.click();
  await flushPromises();
  const confirmBtn = document.querySelector("#evaluation-finalize-confirm-button");
  confirmBtn.click();
  await flushPromises();

  const cancelEvt2 = new Event("cancel", { cancelable: true });
  dialog.dispatchEvent(cancelEvt2);
  assert(cancelEvt2.defaultPrevented, "Evento cancel DEBE ser prevenido mientras la finalización está en curso");

  // Resolver la finalización pendiente
  resolveFinalize();
  await flushPromises();

  controller.destroy();
});

test("HU-025: consulta BORRADOR abierta en readOnly oculta botones de acción, inhabilita inputs y no renderiza botones Editar de diagnósticos", async () => {
  const PATIENT_ID = "patient-hu025-ro";
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          patientId: PATIENT_ID,
          status: "BORRADOR",
          reason: "Consulta de prueba",
          veterinarianId: VET_ID,
          diagnoses: [
            { id: "diag-1", diagnosisType: "PRESUNTIVO", description: "Gastritis presuntiva" }
          ],
          prescriptions: []
        };
      }
      if (url === `/api/v1/patients/${PATIENT_ID}`) {
        return {
          id: PATIENT_ID,
          medicalRecord: { allergies: "Penicilina", medicalAlerts: "Cardiopatía" }
        };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"] });

  await controller.openEvaluation(CONSULTATION_ID, {
    readOnly: true,
    patientProfile: {
      id: PATIENT_ID,
      medicalRecord: { allergies: "Penicilina", medicalAlerts: "Cardiopatía" }
    }
  });

  // Inputs inhabilitados
  assert(document.querySelector("#evaluation-reason").disabled, "El motivo debe estar deshabilitado en readOnly");
  assert(document.querySelector("#evaluation-temperature").disabled, "Temperatura debe estar deshabilitada en readOnly");
  // Botones de acción ocultos
  assert(document.querySelector("#evaluation-save-button").hidden, "Botón guardar debe estar oculto en readOnly");
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Botón finalizar debe estar oculto en readOnly");
  assert(document.querySelector("#diagnosis-add-button").hidden, "Botón añadir diagnóstico debe estar oculto en readOnly");
  assert(document.querySelector("#prescription-add-button").hidden, "Botón añadir prescripción debe estar oculto en readOnly");
  // Botón editar diagnóstico NO debe ser renderizado
  assert(document.querySelector("button[data-action='edit-diagnosis']") === null, "No debe renderizar botones 'Editar' en diagnósticos en readOnly");

  // Alertas médicas visibles
  assert(!document.querySelector("#evaluation-medical-alerts-banner").hidden, "Banner de alertas médicas debe estar visible");
  equal(document.querySelector("#evaluation-allergies-value").textContent, "Penicilina");
  equal(document.querySelector("#evaluation-alerts-value").textContent, "Cardiopatía");
  assert(document.querySelector("#evaluation-no-alerts").hidden, "Caja de sin alertas debe estar oculta");

  controller.destroy();
});

test("HU-025: consulta BORRADOR en readOnly permanece en lectura tras error y reintento de diagnósticos", async () => {
  const PATIENT_ID = "p-1";
  let failDiag = true;
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          patientId: "p-1",
          status: "BORRADOR",
          reason: "Control",
          veterinarianId: VET_ID,
          diagnoses: null, // fuerza llamada a GET /diagnoses
          prescriptions: []
        };
      }
      if (url === `/api/v1/patients/p-1`) {
        return { id: "p-1", medicalRecord: { allergies: "", medicalAlerts: "" } };
      }
      if (url === `/api/v1/consultations/${CONSULTATION_ID}/diagnoses`) {
        if (failDiag) {
          throw new ApiRequestError({ status: 500, message: "Error temporal" });
        }
        return [
          { id: "diag-retry-1", diagnosisType: "DEFINITIVO", description: "Otitis externa" }
        ];
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"] });

  await controller.openEvaluation(CONSULTATION_ID, {
    readOnly: true,
    patientProfile: {
      id: PATIENT_ID,
      medicalRecord: { allergies: "Penicilina", medicalAlerts: "Cardiopatía" }
    }
  });

  assert(!document.querySelector("#evaluation-diagnoses-load-error").hidden, "Error de diagnósticos visible");
  assert(document.querySelector("#diagnosis-add-button").hidden, "Botón añadir diagnóstico oculto");

  // Reintentar carga
  failDiag = false;
  document.querySelector("#evaluation-diagnoses-retry-button").click();
  await flushPromises();

  assert(document.querySelector("#evaluation-diagnoses-load-error").hidden, "Error oculto tras reintento");
  assert(document.querySelector("#diagnosis-add-button").hidden, "Botón añadir diagnóstico DEBE permanecer oculto tras reintento en readOnly");
  assert(document.querySelector("button[data-action='edit-diagnosis']") === null, "Botones 'Editar' diagnósticos NO deben renderizarse tras reintento");
  assert(document.querySelector("#evaluation-save-button").hidden, "Botón guardar debe permanecer oculto");
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Botón finalizar debe permanecer oculto");

  controller.destroy();
});

test("HU-025: apertura desde historia en readOnly y posterior apertura desde agenda restaura edición autorizada", async () => {
  const PATIENT_ID = "p-1";
  const client = {
    requestJson: async (url) => {
      if (url === `/api/v1/consultations/${CONSULTATION_ID}`) {
        return {
          id: CONSULTATION_ID,
          patientId: "p-1",
          status: "BORRADOR",
          reason: "Consulta activa",
          veterinarianId: VET_ID,
          diagnoses: [
            { id: "diag-1", diagnosisType: "PRESUNTIVO", description: "Dermatitis alérgica" }
          ],
          prescriptions: []
        };
      }
      if (url === `/api/v1/patients/p-1`) {
        return { id: "p-1", medicalRecord: { allergies: "", medicalAlerts: "" } };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ", "CONSULTATION_UPDATE", "CONSULTATION_FINALIZE"] });

  // 1. Abrir desde historia en modo de solo lectura
  await controller.openEvaluation(CONSULTATION_ID, {
    readOnly: true,
    patientProfile: {
      id: PATIENT_ID,
      medicalRecord: { allergies: "Penicilina", medicalAlerts: "Cardiopatía" }
    }
  });

  assert(document.querySelector("#evaluation-reason").disabled, "Motivo inhabilitado en readOnly");
  assert(document.querySelector("#evaluation-save-button").hidden, "Botón guardar oculto en readOnly");
  assert(document.querySelector("#evaluation-finalize-button").hidden, "Botón finalizar oculto en readOnly");
  assert(document.querySelector("#diagnosis-add-button").hidden, "Botón añadir diagnóstico oculto en readOnly");
  assert(document.querySelector("button[data-action='edit-diagnosis']") === null, "Sin botones editar diagnósticos");

  // 2. Abrir posteriormente desde la agenda (sin readOnly)
  await controller.openEvaluation(CONSULTATION_ID);

  assert(!document.querySelector("#evaluation-reason").disabled, "Motivo DEBE habilitarse al abrir desde agenda");
  assert(!document.querySelector("#evaluation-save-button").hidden, "Botón guardar DEBE mostrarse al abrir desde agenda");
  assert(!document.querySelector("#evaluation-finalize-button").hidden, "Botón finalizar DEBE mostrarse al abrir desde agenda");
  assert(!document.querySelector("#diagnosis-add-button").hidden, "Botón añadir diagnóstico DEBE mostrarse al abrir desde agenda");
  assert(document.querySelector("button[data-action='edit-diagnosis']") !== null, "Botones 'Editar' diagnósticos DEBEN renderizarse al abrir desde agenda");

  controller.destroy();
});

test("HU-025: alertas médicas reflejan datos reales, estado vacío si no hay alertas y no cruzan datos entre pacientes", async () => {
  const client = {
    requestJson: async (url) => {
      if (url === "/api/v1/consultations/c-patient-a") {
        return {
          id: "c-patient-a",
          patientId: "patient-a",
          status: "BORRADOR",
          reason: "Consulta A",
          veterinarianId: VET_ID,
          diagnoses: [],
          prescriptions: []
        };
      }
      if (url === "/api/v1/consultations/c-patient-b") {
        return {
          id: "c-patient-b",
          patientId: "patient-b",
          status: "BORRADOR",
          reason: "Consulta B",
          veterinarianId: VET_ID,
          diagnoses: [],
          prescriptions: []
        };
      }
      throw new Error(`Inesperado: ${url}`);
    }
  };

  const controller = createConsultationEvaluationController({ document, client, onBackToAppointments: () => {} });
  controller.sessionReady({ userId: VET_ID, authorities: ["CONSULTATION_READ"] });

  // 1. Abrir consulta de paciente B pasando perfil con id 'patient-a' (cruce de datos simulado)
  await controller.openEvaluation("c-patient-b", {
    readOnly: true,
    patientProfile: {
      id: "patient-a",
      medicalRecord: { allergies: "Penicilina", medicalAlerts: "Alergia crítica a sulfas" }
    }
  });

  // Verificar que los datos de A NO se muestran en B porque el patientId de la consulta es 'patient-b'
  assert(document.querySelector("#evaluation-medical-alerts-banner").hidden, "Banner de alertas de A NO debe mostrarse en consulta de B");
  assert(!document.querySelector("#evaluation-no-alerts").hidden, "Caja 'sin alertas' debe estar visible para B");
  equal(document.querySelector("#evaluation-allergies-value").textContent, "", "Alergias deben permanecer vacías en B");

  // 2. Abrir paciente B con su propio perfil (sin alertas)
  await controller.openEvaluation("c-patient-b", {
    readOnly: true,
    patientProfile: {
      id: "patient-b",
      medicalRecord: { allergies: "", medicalAlerts: "" }
    }
  });
  assert(document.querySelector("#evaluation-medical-alerts-banner").hidden, "Banner de alertas debe estar oculto para B");
  assert(!document.querySelector("#evaluation-no-alerts").hidden, "Caja 'sin alertas' debe estar visible para B");

  // 3. Abrir paciente A con su propio perfil (con alertas)
  await controller.openEvaluation("c-patient-a", {
    readOnly: true,
    patientProfile: {
      id: "patient-a",
      medicalRecord: { allergies: "Penicilina", medicalAlerts: "Alergia crítica a sulfas" }
    }
  });

  assert(!document.querySelector("#evaluation-medical-alerts-banner").hidden, "Banner de alertas debe estar visible para A");
  equal(document.querySelector("#evaluation-allergies-value").textContent, "Penicilina");
  equal(document.querySelector("#evaluation-alerts-value").textContent, "Alergia crítica a sulfas");
  assert(document.querySelector("#evaluation-no-alerts").hidden, "Caja 'sin alertas' debe estar oculta para A");

  controller.destroy();
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
  await new Promise((resolve) => setTimeout(resolve, 20));
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
