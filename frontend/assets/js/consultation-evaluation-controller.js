import { ApiRequestError } from "./api-client.js";

const CONSULTATION_READ = "CONSULTATION_READ";
const CONSULTATION_UPDATE = "CONSULTATION_UPDATE";

export function createConsultationEvaluationController({
  document,
  client,
  onBackToAppointments
}) {
  const elements = findElements(document);
  if (!elements) {
    return {
      sessionReady: () => {},
      sessionEnded: () => {},
      openEvaluation: () => {}
    };
  }

  let canRead = false;
  let canUpdate = false;
  let currentUserId = null;
  let currentConsultationId = null;
  let currentConsultation = null;
  let isSaving = false;
  let activeRequestId = 0;

  // Event Listeners
  elements.backButton?.addEventListener("click", handleBack);
  elements.cancelButton?.addEventListener("click", handleBack);
  elements.form?.addEventListener("submit", handleSubmit);
  elements.reasonInput?.addEventListener("input", updateReasonCounter);

  function sessionReady(identity) {
    const authorities = new Set(Array.isArray(identity?.authorities) ? identity.authorities : []);
    canRead = authorities.has(CONSULTATION_READ);
    canUpdate = authorities.has(CONSULTATION_UPDATE);
    currentUserId = typeof identity?.userId === "string" && identity.userId.trim()
      ? identity.userId.trim()
      : null;
  }

  function sessionEnded() {
    activeRequestId++;
    canRead = false;
    canUpdate = false;
    currentUserId = null;
    currentConsultationId = null;
    currentConsultation = null;
    setSavingState(false);
    resetForm();
    resetContext();
    setFormEditable(false);
    resetAlerts();
    if (elements.view) {
      elements.view.hidden = true;
    }
  }

  function resetForm() {
    if (elements.consultationIdInput) elements.consultationIdInput.value = "";
    if (elements.reasonInput) elements.reasonInput.value = "";
    if (elements.reasonCounter) elements.reasonCounter.textContent = "0 / 500";
    if (elements.anamnesisInput) elements.anamnesisInput.value = "";
    if (elements.physicalExamInput) elements.physicalExamInput.value = "";
    if (elements.temperatureInput) elements.temperatureInput.value = "";
    if (elements.weightInput) elements.weightInput.value = "";
    if (elements.heartRateInput) elements.heartRateInput.value = "";
    if (elements.respiratoryRateInput) elements.respiratoryRateInput.value = "";
    if (elements.crtInput) elements.crtInput.value = "";
    if (elements.mucousInput) elements.mucousInput.value = "";
    if (elements.observationsInput) elements.observationsInput.value = "";
    clearValidationErrors();
  }

  function resetContext() {
    if (elements.contextPatient) elements.contextPatient.textContent = "—";
    if (elements.contextVeterinarian) elements.contextVeterinarian.textContent = "—";
    if (elements.contextBranch) elements.contextBranch.textContent = "—";
    if (elements.contextStatus) {
      elements.contextStatus.textContent = "BORRADOR";
      elements.contextStatus.className = "badge bg-secondary";
    }
  }

  async function openEvaluation(consultationId) {
    if (!consultationId) return;
    const requestId = ++activeRequestId;
    currentConsultationId = null;
    currentConsultation = null;

    showOnlyEvaluationView();
    resetAlerts();
    clearValidationErrors();
    resetForm();
    resetContext();
    setSavingState(false);
    setFormEditable(false);

    if (!canRead) {
      setViewState("forbidden");
      return;
    }

    setViewState("loading");

    try {
      const consultation = await client.requestJson(`/api/v1/consultations/${encodeURIComponent(consultationId)}`);
      if (requestId !== activeRequestId) return;

      currentConsultationId = consultationId;
      currentConsultation = consultation;
      populateContext(consultation);
      populateForm(consultation);

      const isEditable = canUpdate && consultation.status === "BORRADOR";
      setFormEditable(isEditable);
      setViewState("content");
      if (isEditable) {
        elements.reasonInput?.focus({ preventScroll: true });
      }
    } catch (error) {
      if (requestId !== activeRequestId) return;

      currentConsultationId = null;
      currentConsultation = null;
      resetForm();
      resetContext();
      setFormEditable(false);

      if (error instanceof ApiRequestError && error.status === 403) {
        setViewState("forbidden");
      } else if (error instanceof ApiRequestError && error.status === 404) {
        setViewState("not-found");
      } else {
        const message = (error instanceof ApiRequestError && error.message)
          ? error.message
          : "No fue posible cargar los datos de la consulta clínica.";
        const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
          ? ` (ID de correlación: ${error.correlationId})`
          : "";
        showError(message, correlationSuffix);
        setViewState("error");
      }
    }
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (!currentConsultationId || !currentConsultation || isSaving) return;
    if (!canUpdate || currentConsultation.status !== "BORRADOR") return;

    resetAlerts();
    clearValidationErrors();

    const validationErrors = validateForm();
    if (validationErrors.length > 0) {
      const firstInvalidField = validationErrors[0].element;
      showValidationSummary("Por favor corrige los campos indicados antes de guardar la evaluación.");
      firstInvalidField?.focus({ preventScroll: true });
      return;
    }

    const payload = buildPayload();
    const submissionRequestId = activeRequestId;
    const targetConsultationId = currentConsultationId;
    setSavingState(true);

    try {
      const updated = await client.requestJson(`/api/v1/consultations/${encodeURIComponent(targetConsultationId)}/evaluation`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify(payload)
      });
      if (submissionRequestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }
      currentConsultation = updated;
      populateContext(updated);
      showNotice("Evaluación clínica guardada correctamente en borrador.");
      elements.notice?.focus({ preventScroll: true });
    } catch (error) {
      if (submissionRequestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }

      // Form values are intentionally preserved on error
      const message = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible guardar la evaluación clínica.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";

      if (error instanceof ApiRequestError && error.status === 409) {
        if (currentConsultation) {
          currentConsultation.status = "CONFLICTO";
        }
        setFormEditable(false);
        showError(message, correlationSuffix);
        elements.error?.focus({ preventScroll: true });
        return;
      }

      if (error instanceof ApiRequestError && Array.isArray(error.fieldErrors) && error.fieldErrors.length > 0) {
        let firstFieldToFocus = null;
        for (const fe of error.fieldErrors) {
          const fieldEl = getFieldElementByName(fe.field);
          if (fieldEl) {
            fieldEl.setAttribute("aria-invalid", "true");
            if (!firstFieldToFocus) firstFieldToFocus = fieldEl;
          }
        }
        showValidationSummary(message + correlationSuffix);
        firstFieldToFocus?.focus({ preventScroll: true });
      } else {
        showError(message, correlationSuffix);
        elements.error?.focus({ preventScroll: true });
      }
    } finally {
      if (submissionRequestId === activeRequestId && targetConsultationId === currentConsultationId) {
        setSavingState(false);
      }
    }
  }

  function validateForm() {
    const invalidFields = [];

    // Reason is mandatory
    const reasonVal = elements.reasonInput?.value.trim() ?? "";
    if (!reasonVal) {
      elements.reasonInput?.setAttribute("aria-invalid", "true");
      invalidFields.push({ element: elements.reasonInput, field: "reason" });
    }

    // Temperature (30.0 - 45.0)
    const tempVal = elements.temperatureInput?.value.trim() ?? "";
    if (tempVal) {
      const num = Number(tempVal);
      if (isNaN(num) || num < 30.0 || num > 45.0) {
        elements.temperatureInput?.setAttribute("aria-invalid", "true");
        invalidFields.push({ element: elements.temperatureInput, field: "temperature" });
      }
    }

    // Weight (0.01 - 500.0)
    const weightVal = elements.weightInput?.value.trim() ?? "";
    if (weightVal) {
      const num = Number(weightVal);
      if (isNaN(num) || num <= 0 || num > 500.0) {
        elements.weightInput?.setAttribute("aria-invalid", "true");
        invalidFields.push({ element: elements.weightInput, field: "weight" });
      }
    }

    // Heart Rate (20 - 400)
    const hrVal = elements.heartRateInput?.value.trim() ?? "";
    if (hrVal) {
      const num = Number(hrVal);
      if (isNaN(num) || !Number.isInteger(num) || num < 20 || num > 400) {
        elements.heartRateInput?.setAttribute("aria-invalid", "true");
        invalidFields.push({ element: elements.heartRateInput, field: "heartRate" });
      }
    }

    // Respiratory Rate (5 - 200)
    const rrVal = elements.respiratoryRateInput?.value.trim() ?? "";
    if (rrVal) {
      const num = Number(rrVal);
      if (isNaN(num) || !Number.isInteger(num) || num < 5 || num > 200) {
        elements.respiratoryRateInput?.setAttribute("aria-invalid", "true");
        invalidFields.push({ element: elements.respiratoryRateInput, field: "respiratoryRate" });
      }
    }

    // CRT (0.1 - 10.0)
    const crtVal = elements.crtInput?.value.trim() ?? "";
    if (crtVal) {
      const num = Number(crtVal);
      if (isNaN(num) || num < 0.1 || num > 10.0) {
        elements.crtInput?.setAttribute("aria-invalid", "true");
        invalidFields.push({ element: elements.crtInput, field: "crt" });
      }
    }

    return invalidFields;
  }

  function buildPayload() {
    const reason = elements.reasonInput?.value.trim();
    const anamnesis = elements.anamnesisInput?.value.trim() || null;
    const physicalExam = elements.physicalExamInput?.value.trim() || null;
    const observations = elements.observationsInput?.value.trim() || null;

    let vitalSigns = null;
    const temp = elements.temperatureInput?.value.trim();
    const weight = elements.weightInput?.value.trim();
    const hr = elements.heartRateInput?.value.trim();
    const rr = elements.respiratoryRateInput?.value.trim();
    const crt = elements.crtInput?.value.trim();
    const mucous = elements.mucousInput?.value.trim();

    if (temp || weight || hr || rr || crt || mucous) {
      vitalSigns = {
        temperatureCelsius: temp ? Number(temp) : null,
        weightKg: weight ? Number(weight) : null,
        heartRateBpm: hr ? parseInt(hr, 10) : null,
        respiratoryRateBpm: rr ? parseInt(rr, 10) : null,
        capillaryRefillTimeSeconds: crt ? Number(crt) : null,
        mucousMembranes: mucous || null
      };
    }

    const variableDetails = (currentConsultation?.clinicalDetails?.variableDetails)
      ? currentConsultation.clinicalDetails.variableDetails
      : null;

    return {
      reason,
      anamnesis,
      physicalExam,
      vitalSigns,
      observations,
      variableDetails
    };
  }

  function populateContext(consultation) {
    if (elements.contextPatient) {
      elements.contextPatient.textContent = consultation.patientName || "—";
    }
    if (elements.contextVeterinarian) {
      elements.contextVeterinarian.textContent = consultation.veterinarianFullName || "—";
    }
    if (elements.contextBranch) {
      elements.contextBranch.textContent = consultation.branchName || "—";
    }
    if (elements.contextStatus) {
      elements.contextStatus.textContent = consultation.status || "BORRADOR";
      elements.contextStatus.className = consultation.status === "BORRADOR"
        ? "badge bg-secondary"
        : "badge bg-success";
    }
  }

  function populateForm(consultation) {
    if (elements.consultationIdInput) {
      elements.consultationIdInput.value = consultation.id;
    }
    if (elements.reasonInput) {
      elements.reasonInput.value = consultation.reason || "";
      updateReasonCounter();
    }
    if (elements.anamnesisInput) {
      elements.anamnesisInput.value = consultation.anamnesis || "";
    }
    if (elements.physicalExamInput) {
      elements.physicalExamInput.value = consultation.physicalExam || "";
    }

    const signs = consultation.vitalSigns || consultation.clinicalDetails?.vitalSigns;
    if (elements.temperatureInput) {
      elements.temperatureInput.value = signs?.temperatureCelsius ?? "";
    }
    if (elements.weightInput) {
      elements.weightInput.value = signs?.weightKg ?? "";
    }
    if (elements.heartRateInput) {
      elements.heartRateInput.value = signs?.heartRateBpm ?? "";
    }
    if (elements.respiratoryRateInput) {
      elements.respiratoryRateInput.value = signs?.respiratoryRateBpm ?? "";
    }
    if (elements.crtInput) {
      elements.crtInput.value = signs?.capillaryRefillTimeSeconds ?? "";
    }
    if (elements.mucousInput) {
      elements.mucousInput.value = signs?.mucousMembranes ?? "";
    }

    if (elements.observationsInput) {
      elements.observationsInput.value = consultation.clinicalDetails?.observations || "";
    }
  }

  function setFormEditable(isEditable) {
    const inputs = [
      elements.reasonInput,
      elements.anamnesisInput,
      elements.physicalExamInput,
      elements.temperatureInput,
      elements.weightInput,
      elements.heartRateInput,
      elements.respiratoryRateInput,
      elements.crtInput,
      elements.mucousInput,
      elements.observationsInput
    ];
    for (const input of inputs) {
      if (input) {
        input.disabled = !isEditable;
      }
    }
    if (elements.saveButton) {
      elements.saveButton.disabled = !isEditable;
      elements.saveButton.hidden = !isEditable;
    }
  }

  function setSavingState(saving) {
    isSaving = saving;
    if (elements.saveButton) {
      const isEditable = canUpdate && currentConsultation?.status === "BORRADOR";
      elements.saveButton.disabled = saving || !isEditable;
      elements.saveButton.hidden = !isEditable;
      elements.saveButton.setAttribute("aria-busy", String(saving));
    }
    if (elements.saveSpinner) {
      elements.saveSpinner.hidden = !saving;
    }
    if (elements.saveLabel) {
      elements.saveLabel.textContent = saving ? "Guardando..." : "Guardar evaluación";
    }
  }

  function updateReasonCounter() {
    if (!elements.reasonCounter || !elements.reasonInput) return;
    const len = elements.reasonInput.value.length;
    elements.reasonCounter.textContent = `${len} / 500`;
  }

  function clearValidationErrors() {
    const inputs = [
      elements.reasonInput,
      elements.temperatureInput,
      elements.weightInput,
      elements.heartRateInput,
      elements.respiratoryRateInput,
      elements.crtInput,
      elements.mucousInput
    ];
    for (const input of inputs) {
      input?.removeAttribute("aria-invalid");
    }
    if (elements.validationSummary) {
      elements.validationSummary.hidden = true;
    }
  }

  function resetAlerts() {
    if (elements.notice) elements.notice.hidden = true;
    if (elements.error) elements.error.hidden = true;
    if (elements.forbidden) elements.forbidden.hidden = true;
    if (elements.notFound) elements.notFound.hidden = true;
    if (elements.validationSummary) elements.validationSummary.hidden = true;
  }

  function showNotice(message) {
    if (!elements.notice) return;
    elements.notice.textContent = message;
    elements.notice.hidden = false;
  }

  function showError(message, correlationSuffix = "") {
    if (!elements.error) return;
    if (elements.errorMessage) {
      elements.errorMessage.textContent = message;
    }
    if (elements.errorCorrelation) {
      if (correlationSuffix) {
        elements.errorCorrelation.textContent = correlationSuffix;
        elements.errorCorrelation.hidden = false;
      } else {
        elements.errorCorrelation.hidden = true;
      }
    }
    elements.error.hidden = false;
  }

  function showValidationSummary(message) {
    if (!elements.validationSummary) return;
    if (elements.validationMessage) {
      elements.validationMessage.textContent = message;
    }
    elements.validationSummary.hidden = false;
  }

  function setViewState(state) {
    if (elements.loadingState) elements.loadingState.hidden = state !== "loading";
    if (elements.contentContainer) elements.contentContainer.hidden = state !== "content";
    if (elements.forbidden) elements.forbidden.hidden = state !== "forbidden";
    if (elements.notFound) elements.notFound.hidden = state !== "not-found";

    if (state === "forbidden") elements.forbidden?.focus({ preventScroll: true });
    if (state === "not-found") elements.notFound?.focus({ preventScroll: true });
    if (state === "error") elements.error?.focus({ preventScroll: true });
  }

  function showOnlyEvaluationView() {
    if (elements.view) {
      elements.view.hidden = false;
    }
    for (const otherView of [
      document.querySelector("#users-view"),
      document.querySelector("#roles-view"),
      document.querySelector("#owners-view"),
      document.querySelector("#patients-view"),
      document.querySelector("#patient-profile-view"),
      document.querySelector("#appointments-view"),
      document.querySelector("#dashboard-home")
    ]) {
      if (otherView) otherView.hidden = true;
    }
  }

  function handleBack() {
    if (elements.view) {
      elements.view.hidden = true;
    }
    onBackToAppointments?.();
  }

  function getFieldElementByName(fieldName) {
    switch (fieldName) {
      case "reason": return elements.reasonInput;
      case "temperatureCelsius":
      case "temperature": return elements.temperatureInput;
      case "weightKg":
      case "weight": return elements.weightInput;
      case "heartRateBpm":
      case "heartRate": return elements.heartRateInput;
      case "respiratoryRateBpm":
      case "respiratoryRate": return elements.respiratoryRateInput;
      case "capillaryRefillTimeSeconds":
      case "crt": return elements.crtInput;
      case "mucousMembranes": return elements.mucousInput;
      default: return null;
    }
  }

  function destroy() {
    sessionEnded();
    elements.backButton?.removeEventListener("click", handleBack);
    elements.cancelButton?.removeEventListener("click", handleBack);
    elements.form?.removeEventListener("submit", handleSubmit);
    elements.reasonInput?.removeEventListener("input", updateReasonCounter);
  }

  return {
    sessionReady,
    sessionEnded,
    openEvaluation,
    destroy
  };
}

function findElements(document) {
  const view = document.querySelector("#consultation-evaluation-view");
  if (!view) return null;

  return {
    view,
    backButton: document.querySelector("#consultation-evaluation-back-button"),
    cancelButton: document.querySelector("#evaluation-cancel-button"),
    saveButton: document.querySelector("#evaluation-save-button"),
    saveLabel: document.querySelector("#evaluation-save-label"),
    saveSpinner: document.querySelector("#evaluation-save-spinner"),
    form: document.querySelector("#consultation-evaluation-form"),
    loadingState: document.querySelector("#evaluation-loading-state"),
    contentContainer: document.querySelector("#evaluation-content-container"),
    forbidden: document.querySelector("#evaluation-forbidden"),
    notFound: document.querySelector("#evaluation-not-found"),
    notice: document.querySelector("#evaluation-notice"),
    error: document.querySelector("#evaluation-error"),
    errorMessage: document.querySelector("#evaluation-error-message"),
    errorCorrelation: document.querySelector("#evaluation-error-correlation"),
    validationSummary: document.querySelector("#evaluation-validation"),
    validationMessage: document.querySelector("#evaluation-validation-message"),
    contextPatient: document.querySelector("#evaluation-context-patient"),
    contextVeterinarian: document.querySelector("#evaluation-context-veterinarian"),
    contextBranch: document.querySelector("#evaluation-context-branch"),
    contextStatus: document.querySelector("#evaluation-context-status"),
    consultationIdInput: document.querySelector("#evaluation-consultation-id"),
    reasonInput: document.querySelector("#evaluation-reason"),
    reasonCounter: document.querySelector("#evaluation-reason-counter"),
    anamnesisInput: document.querySelector("#evaluation-anamnesis"),
    physicalExamInput: document.querySelector("#evaluation-physical-exam"),
    temperatureInput: document.querySelector("#evaluation-temperature"),
    weightInput: document.querySelector("#evaluation-weight"),
    heartRateInput: document.querySelector("#evaluation-heart-rate"),
    respiratoryRateInput: document.querySelector("#evaluation-respiratory-rate"),
    crtInput: document.querySelector("#evaluation-crt"),
    mucousInput: document.querySelector("#evaluation-mucous-membranes"),
    observationsInput: document.querySelector("#evaluation-observations")
  };
}
