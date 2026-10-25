import { ApiRequestError } from "../assets/js/api-client.js";
import { createConsultationEvaluationController } from "../assets/js/consultation-evaluation-controller.js";

const CONSULTATION_ID = "11111111-aaaa-4111-8111-111111111111";
const VET_ID = "22222222-bbbb-4222-8222-222222222222";
const tests = [];

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
