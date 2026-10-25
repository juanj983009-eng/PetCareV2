import { ApiRequestError } from "./api-client.js";

const CONSULTATION_READ = "CONSULTATION_READ";
const CONSULTATION_UPDATE = "CONSULTATION_UPDATE";
const CONSULTATION_FINALIZE = "CONSULTATION_FINALIZE";

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
  let canFinalize = false;
  let currentUserId = null;
  let currentConsultationId = null;
  let currentConsultation = null;
  let isSaving = false;
  let isFinalizing = false;
  let initialFormSnapshot = null;
  let activeRequestId = 0;
  let currentDiagnoses = [];
  let isSavingDiagnosis = false;
  let editingDiagnosisId = null;
  let currentPrescriptions = [];
  let isSavingPrescription = false;
  let currentPrescriptionIdempotencyKey = null;

  // Event Listeners
  elements.backButton?.addEventListener("click", handleBack);
  elements.cancelButton?.addEventListener("click", handleBack);
  elements.finalizeButton?.addEventListener("click", handleOpenFinalizeDialog);
  elements.finalizeCancelButton?.addEventListener("click", handleCloseFinalizeDialog);
  elements.finalizeConfirmButton?.addEventListener("click", handleFinalizeSubmit);
  elements.finalizeDialog?.addEventListener("cancel", handleNativeDialogCancel);
  elements.form?.addEventListener("submit", handleSubmit);
  elements.reasonInput?.addEventListener("input", updateReasonCounter);

  const evaluationInputs = [
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
  for (const input of evaluationInputs) {
    input?.addEventListener("input", handleEvaluationInputChange);
    input?.addEventListener("change", handleEvaluationInputChange);
  }

  elements.diagnosisAddButton?.addEventListener("click", handleOpenAddDiagnosis);
  elements.diagnosisCancelButton?.addEventListener("click", handleCloseDiagnosisForm);
  elements.diagnosisForm?.addEventListener("submit", handleDiagnosisSubmit);
  elements.diagnosisDescriptionInput?.addEventListener("input", updateDiagnosisDescriptionCounter);
  elements.diagnosesRetryButton?.addEventListener("click", handleRetryDiagnoses);
  elements.prescriptionAddButton?.addEventListener("click", handleOpenPrescriptionForm);
  elements.prescriptionCancelButton?.addEventListener("click", handleClosePrescriptionForm);
  elements.prescriptionForm?.addEventListener("submit", handlePrescriptionSubmit);
  elements.prescriptionAddItemRowButton?.addEventListener("click", handleAddPrescriptionItemRow);
  elements.prescriptionGeneralInstructions?.addEventListener("input", updatePrescriptionInstructionsCounter);
  elements.prescriptionsRetryButton?.addEventListener("click", handleRetryPrescriptions);

  function handleEvaluationInputChange() {
    updateFinalizeButtonState();
  }

  function handleNativeDialogCancel(event) {
    if (isFinalizing) {
      event.preventDefault();
      return;
    }
    handleCloseFinalizeDialog();
  }

  function sessionReady(identity) {
    const authorities = new Set(Array.isArray(identity?.authorities) ? identity.authorities : []);
    canRead = authorities.has(CONSULTATION_READ);
    canUpdate = authorities.has(CONSULTATION_UPDATE);
    canFinalize = authorities.has(CONSULTATION_FINALIZE);
    currentUserId = typeof identity?.userId === "string" && identity.userId.trim()
      ? identity.userId.trim()
      : null;
  }

  function sessionEnded() {
    activeRequestId++;
    canRead = false;
    canUpdate = false;
    canFinalize = false;
    isFinalizing = false;
    initialFormSnapshot = null;
    currentUserId = null;
    currentConsultationId = null;
    currentConsultation = null;
    closeFinalizeDialog();
    setSavingState(false);
    setSavingDiagnosisState(false);
    setSavingPrescriptionState(false);
    resetForm();
    resetContext();
    resetDiagnosisForm();
    resetDiagnosisAlerts();
    resetPrescriptionForm();
    resetPrescriptionAlerts();
    currentDiagnoses = [];
    editingDiagnosisId = null;
    currentPrescriptions = [];
    renderDiagnoses([]);
    renderPrescriptions([]);
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
    initialFormSnapshot = null;
    isFinalizing = false;
    closeFinalizeDialog();
    setFinalizingState(false);

    showOnlyEvaluationView();
    resetAlerts();
    clearValidationErrors();
    resetForm();
    resetContext();
    resetDiagnosisForm();
    resetDiagnosisAlerts();
    resetPrescriptionForm();
    resetPrescriptionAlerts();
    currentDiagnoses = [];
    editingDiagnosisId = null;
    currentPrescriptions = [];
    if (elements.diagnosisAddButton) elements.diagnosisAddButton.hidden = true;
    if (elements.prescriptionAddButton) elements.prescriptionAddButton.hidden = true;
    if (elements.finalizeButton) {
      elements.finalizeButton.hidden = true;
      elements.finalizeButton.disabled = true;
    }
    setSavingState(false);
    setSavingDiagnosisState(false);
    setSavingPrescriptionState(false);
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
      initialFormSnapshot = buildSnapshotFromConsultation(consultation);

      const isEditable = canUpdate && consultation.status === "BORRADOR";
      setFormEditable(isEditable);
      updateFinalizeButtonState();
      setViewState("content");
      if (Array.isArray(consultation.diagnoses)) {
        currentDiagnoses = consultation.diagnoses;
        renderDiagnoses(currentDiagnoses);
        if (elements.diagnosisAddButton) {
          elements.diagnosisAddButton.hidden = !isEditable;
        }
      } else {
        await loadDiagnoses(consultationId, requestId);
      }

      const isVetAssigned = !consultation.veterinarianId || !currentUserId || consultation.veterinarianId === currentUserId;
      if (Array.isArray(consultation.prescriptions)) {
        currentPrescriptions = consultation.prescriptions;
        renderPrescriptions(currentPrescriptions);
        if (elements.prescriptionAddButton) {
          elements.prescriptionAddButton.hidden = !(isEditable && isVetAssigned);
        }
      } else if (consultation.prescriptions === null) {
        await loadPrescriptions(consultationId, requestId);
      } else {
        currentPrescriptions = [];
        renderPrescriptions([]);
        if (elements.prescriptionAddButton) {
          elements.prescriptionAddButton.hidden = !(isEditable && isVetAssigned);
        }
      }
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
      initialFormSnapshot = buildSnapshotFromConsultation(updated);
      populateContext(updated);
      updateFinalizeButtonState();
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
        updateFinalizeButtonState();
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
    updateFinalizeButtonState();
    if (elements.diagnosisAddButton) {
      elements.diagnosisAddButton.hidden = !isEditable;
    }
    if (elements.prescriptionAddButton) {
      const isVetAssigned = !currentConsultation?.veterinarianId || !currentUserId || currentConsultation.veterinarianId === currentUserId;
      elements.prescriptionAddButton.hidden = !(isEditable && isVetAssigned);
    }
  }

  function updateFinalizeButtonState() {
    if (!elements.finalizeButton) return;
    const isVetAssigned = !currentConsultation?.veterinarianId || !currentUserId || currentConsultation.veterinarianId === currentUserId;
    const isEditable = canUpdate && currentConsultation?.status === "BORRADOR";
    const canFinalizeNow = isEditable && canFinalize && isVetAssigned;
    elements.finalizeButton.hidden = !canFinalizeNow;
    const hasUnsavedChanges = hasPendingChanges();
    const isAnyOpBusy = isSaving || isFinalizing || isSavingDiagnosis || isSavingPrescription;
    elements.finalizeButton.disabled = !canFinalizeNow || hasUnsavedChanges || isAnyOpBusy;
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
    updateFinalizeButtonState();
  }

  function setFinalizingState(finalizing) {
    isFinalizing = finalizing;
    updateFinalizeButtonState();
    if (elements.finalizeSpinner) {
      elements.finalizeSpinner.hidden = !finalizing;
    }
    if (elements.finalizeLabel) {
      elements.finalizeLabel.textContent = finalizing ? "Finalizando..." : "Finalizar consulta";
    }
    if (elements.finalizeConfirmButton) {
      elements.finalizeConfirmButton.disabled = finalizing;
      elements.finalizeConfirmButton.setAttribute("aria-busy", String(finalizing));
    }
    if (elements.finalizeConfirmSpinner) {
      elements.finalizeConfirmSpinner.hidden = !finalizing;
    }
    if (elements.finalizeConfirmLabel) {
      elements.finalizeConfirmLabel.textContent = finalizing ? "Finalizando..." : "Confirmar y finalizar";
    }
    if (elements.finalizeCancelButton) {
      elements.finalizeCancelButton.disabled = finalizing;
    }
    if (elements.saveButton) {
      elements.saveButton.disabled = finalizing || isSaving || currentConsultation?.status !== "BORRADOR";
    }
  }

  function buildSnapshotFromConsultation(consultation) {
    if (!consultation) return null;
    const signs = consultation.vitalSigns || consultation.clinicalDetails?.vitalSigns || {};
    const obs = consultation.observations || consultation.clinicalDetails?.observations || "";
    return JSON.stringify({
      reason: (consultation.reason || "").trim(),
      anamnesis: (consultation.anamnesis || "").trim(),
      physicalExam: (consultation.physicalExam || "").trim(),
      temperature: signs.temperatureCelsius != null && !isNaN(signs.temperatureCelsius) ? String(Number(signs.temperatureCelsius)) : "",
      weight: signs.weightKg != null && !isNaN(signs.weightKg) ? String(Number(signs.weightKg)) : "",
      heartRate: signs.heartRateBpm != null && !isNaN(signs.heartRateBpm) ? String(parseInt(signs.heartRateBpm, 10)) : "",
      respiratoryRate: signs.respiratoryRateBpm != null && !isNaN(signs.respiratoryRateBpm) ? String(parseInt(signs.respiratoryRateBpm, 10)) : "",
      crt: signs.capillaryRefillTimeSeconds != null && !isNaN(signs.capillaryRefillTimeSeconds) ? String(Number(signs.capillaryRefillTimeSeconds)) : "",
      mucousMembranes: (signs.mucousMembranes || "").trim(),
      observations: (obs || "").trim()
    });
  }

  function getCurrentEvaluationFormSnapshot() {
    const temp = (elements.temperatureInput?.value || "").trim();
    const weight = (elements.weightInput?.value || "").trim();
    const hr = (elements.heartRateInput?.value || "").trim();
    const rr = (elements.respiratoryRateInput?.value || "").trim();
    const crt = (elements.crtInput?.value || "").trim();

    return JSON.stringify({
      reason: (elements.reasonInput?.value || "").trim(),
      anamnesis: (elements.anamnesisInput?.value || "").trim(),
      physicalExam: (elements.physicalExamInput?.value || "").trim(),
      temperature: temp && !isNaN(temp) ? String(Number(temp)) : "",
      weight: weight && !isNaN(weight) ? String(Number(weight)) : "",
      heartRate: hr && !isNaN(hr) ? String(parseInt(hr, 10)) : "",
      respiratoryRate: rr && !isNaN(rr) ? String(parseInt(rr, 10)) : "",
      crt: crt && !isNaN(crt) ? String(Number(crt)) : "",
      mucousMembranes: (elements.mucousInput?.value || "").trim(),
      observations: (elements.observationsInput?.value || "").trim()
    });
  }

  function hasPendingChanges() {
    if (initialFormSnapshot !== null && getCurrentEvaluationFormSnapshot() !== initialFormSnapshot) {
      return true;
    }
    if (elements.diagnosisForm && !elements.diagnosisForm.hidden) {
      return true;
    }
    if (elements.prescriptionForm && !elements.prescriptionForm.hidden) {
      return true;
    }
    return false;
  }

  function handleOpenFinalizeDialog() {
    if (!canFinalize || !currentConsultationId || isFinalizing) return;
    if (currentConsultation?.status !== "BORRADOR") return;

    resetAlerts();
    if (isSaving || isSavingDiagnosis || isSavingPrescription) {
      showValidationSummary("Hay una operación clínica en curso. Por favor espera a que finalice antes de cerrar la consulta.");
      elements.validationSummary?.focus({ preventScroll: true });
      return;
    }
    if (hasPendingChanges()) {
      showValidationSummary(
        "Tienes modificaciones clínicas sin guardar en la evaluación, diagnósticos o prescripción. Por favor guarda o descarta los cambios pendientes antes de finalizar la consulta."
      );
      elements.validationSummary?.focus({ preventScroll: true });
      return;
    }

    hideFinalizeDialogError();
    if (typeof elements.finalizeDialog?.showModal === "function") {
      try {
        elements.finalizeDialog.showModal();
      } catch (_) {
        if (elements.finalizeDialog) {
          elements.finalizeDialog.hidden = false;
          elements.finalizeDialog.setAttribute("open", "");
        }
      }
    } else if (elements.finalizeDialog) {
      elements.finalizeDialog.hidden = false;
      elements.finalizeDialog.setAttribute("open", "");
    }
  }

  function handleCloseFinalizeDialog() {
    if (isFinalizing) return;
    closeFinalizeDialog();
    elements.finalizeButton?.focus({ preventScroll: true });
  }

  function closeFinalizeDialog() {
    hideFinalizeDialogError();
    if (typeof elements.finalizeDialog?.close === "function") {
      try {
        elements.finalizeDialog.close();
      } catch (_) {}
    } else if (elements.finalizeDialog) {
      elements.finalizeDialog.hidden = true;
      elements.finalizeDialog.removeAttribute("open");
    }
  }

  function hideFinalizeDialogError() {
    if (elements.finalizeDialogError) {
      elements.finalizeDialogError.hidden = true;
    }
    if (elements.finalizeDialogErrorMessage) {
      elements.finalizeDialogErrorMessage.textContent = "";
    }
  }

  function showFinalizeDialogError(error) {
    if (!elements.finalizeDialogError) return;
    const message = (error instanceof ApiRequestError && error.message)
      ? error.message
      : "No fue posible finalizar la consulta clínica.";
    const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
      ? ` (ID de correlación: ${error.correlationId})`
      : "";
    if (elements.finalizeDialogErrorMessage) {
      elements.finalizeDialogErrorMessage.textContent = `${message}${correlationSuffix}`;
    }
    elements.finalizeDialogError.hidden = false;
    elements.finalizeDialogError.focus({ preventScroll: true });
  }

  async function handleFinalizeSubmit() {
    if (isFinalizing || !currentConsultationId || !currentConsultation) return;
    if (!canFinalize || currentConsultation.status !== "BORRADOR") return;

    if (isSaving || isSavingDiagnosis || isSavingPrescription) {
      showFinalizeDialogError(new ApiRequestError({
        message: "Hay una operación clínica en curso. Por favor espera a que finalice.",
        status: 400
      }));
      return;
    }

    if (hasPendingChanges()) {
      showFinalizeDialogError(new ApiRequestError({
        message: "Tienes modificaciones clínicas sin guardar en la evaluación, diagnósticos o prescripción. Por favor guarda o descarta los cambios pendientes antes de finalizar la consulta.",
        status: 400
      }));
      return;
    }

    const requestId = activeRequestId;
    const targetConsultationId = currentConsultationId;
    setFinalizingState(true);
    hideFinalizeDialogError();

    try {
      const updated = await client.requestJson(
        `/api/v1/consultations/${encodeURIComponent(targetConsultationId)}/finalize`,
        { method: "POST" }
      );
      if (requestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }
      currentConsultation = updated;
      closeFinalizeDialog();
      populateContext(updated);
      populateForm(updated);
      initialFormSnapshot = buildSnapshotFromConsultation(updated);
      setFormEditable(false);
      if (Array.isArray(updated.diagnoses)) {
        currentDiagnoses = updated.diagnoses;
        renderDiagnoses(currentDiagnoses);
      }
      if (Array.isArray(updated.prescriptions)) {
        currentPrescriptions = updated.prescriptions;
        renderPrescriptions(currentPrescriptions);
      }
      showNotice("Consulta finalizada correctamente. El expediente clínico se encuentra en modo de solo lectura.");
      elements.notice?.focus({ preventScroll: true });
    } catch (error) {
      if (requestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }
      showFinalizeDialogError(error);
    } finally {
      if (requestId === activeRequestId && targetConsultationId === currentConsultationId) {
        setFinalizingState(false);
      }
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
    elements.finalizeButton?.removeEventListener("click", handleOpenFinalizeDialog);
    elements.finalizeCancelButton?.removeEventListener("click", handleCloseFinalizeDialog);
    elements.finalizeConfirmButton?.removeEventListener("click", handleFinalizeSubmit);
    elements.finalizeDialog?.removeEventListener("cancel", handleNativeDialogCancel);
    elements.form?.removeEventListener("submit", handleSubmit);
    elements.reasonInput?.removeEventListener("input", updateReasonCounter);
    for (const input of evaluationInputs) {
      input?.removeEventListener("input", handleEvaluationInputChange);
      input?.removeEventListener("change", handleEvaluationInputChange);
    }
    elements.diagnosisAddButton?.removeEventListener("click", handleOpenAddDiagnosis);
    elements.diagnosisCancelButton?.removeEventListener("click", handleCloseDiagnosisForm);
    elements.diagnosisForm?.removeEventListener("submit", handleDiagnosisSubmit);
    elements.diagnosisDescriptionInput?.removeEventListener("input", updateDiagnosisDescriptionCounter);
    elements.diagnosesRetryButton?.removeEventListener("click", handleRetryDiagnoses);
    elements.prescriptionAddButton?.removeEventListener("click", handleOpenPrescriptionForm);
    elements.prescriptionCancelButton?.removeEventListener("click", handleClosePrescriptionForm);
    elements.prescriptionForm?.removeEventListener("submit", handlePrescriptionSubmit);
    elements.prescriptionAddItemRowButton?.removeEventListener("click", handleAddPrescriptionItemRow);
    elements.prescriptionGeneralInstructions?.removeEventListener("input", updatePrescriptionInstructionsCounter);
    elements.prescriptionsRetryButton?.removeEventListener("click", handleRetryPrescriptions);
  }

  function handleRetryDiagnoses() {
    if (currentConsultationId) {
      loadDiagnoses(currentConsultationId, activeRequestId);
    }
  }

  // ==========================================
  // HU-022: DIAGNÓSTICOS CLÍNICOS
  // ==========================================

  async function loadDiagnoses(consultationId, requestId) {
    if (!elements.diagnosesSection) return;
    if (elements.diagnosesLoading) elements.diagnosesLoading.hidden = false;
    if (elements.diagnosesList) elements.diagnosesList.hidden = true;
    if (elements.diagnosesEmpty) elements.diagnosesEmpty.hidden = true;
    if (elements.diagnosesLoadError) elements.diagnosesLoadError.hidden = true;

    try {
      const list = await client.requestJson(`/api/v1/consultations/${encodeURIComponent(consultationId)}/diagnoses`);
      if (requestId !== activeRequestId || consultationId !== currentConsultationId) return;

      if (!Array.isArray(list)) {
        throw new ApiRequestError({
          kind: "INVALID_RESPONSE",
          code: "INVALID_RESPONSE",
          message: "La respuesta de diagnósticos clínicos no tiene un formato de lista válido."
        });
      }

      currentDiagnoses = list;
      renderDiagnoses(currentDiagnoses);
      const isEditable = canUpdate && currentConsultation?.status === "BORRADOR";
      if (elements.diagnosisAddButton) {
        elements.diagnosisAddButton.hidden = !isEditable;
      }
    } catch (err) {
      if (requestId !== activeRequestId || consultationId !== currentConsultationId) return;
      currentDiagnoses = [];
      if (elements.diagnosesList) {
        elements.diagnosesList.replaceChildren();
        elements.diagnosesList.hidden = true;
      }
      if (elements.diagnosesEmpty) elements.diagnosesEmpty.hidden = true;
      if (elements.diagnosesCounter) elements.diagnosesCounter.textContent = "0";

      if (elements.diagnosesLoadError) {
        elements.diagnosesLoadError.hidden = false;
        if (elements.diagnosesLoadErrorMessage) {
          elements.diagnosesLoadErrorMessage.textContent =
            err instanceof ApiRequestError
              ? err.message
              : (err?.message || "No fue posible consultar los diagnósticos clínicos de la consulta.");
        }
        if (elements.diagnosesLoadErrorCorrelation) {
          const correlationId = err instanceof ApiRequestError ? err.correlationId : err?.correlationId;
          elements.diagnosesLoadErrorCorrelation.textContent = correlationId
            ? `Código de seguimiento: ${correlationId}`
            : "";
          elements.diagnosesLoadErrorCorrelation.hidden = !correlationId;
        }
        elements.diagnosesLoadError.focus?.({ preventScroll: true });
      }
    } finally {
      if (requestId === activeRequestId && consultationId === currentConsultationId) {
        if (elements.diagnosesLoading) elements.diagnosesLoading.hidden = true;
      }
    }
  }

  function renderDiagnoses(list) {
    if (!elements.diagnosesList) return;
    if (elements.diagnosesLoadError) elements.diagnosesLoadError.hidden = true;
    elements.diagnosesList.replaceChildren();
    if (elements.diagnosesCounter) {
      elements.diagnosesCounter.textContent = String(list.length);
    }
    if (list.length === 0) {
      if (elements.diagnosesEmpty) elements.diagnosesEmpty.hidden = false;
      elements.diagnosesList.hidden = true;
      return;
    }
    if (elements.diagnosesEmpty) elements.diagnosesEmpty.hidden = true;
    elements.diagnosesList.hidden = false;

    const isEditable = canUpdate && currentConsultation?.status === "BORRADOR";
    for (const diag of list) {
      const item = document.createElement("div");
      item.className = "list-group-item d-flex flex-column flex-sm-row justify-content-between align-items-sm-center gap-2 p-3";
      item.dataset.diagnosisId = diag.id;

      const infoCol = document.createElement("div");
      infoCol.className = "flex-grow-1";

      const headerDiv = document.createElement("div");
      headerDiv.className = "d-flex flex-wrap align-items-center gap-2 mb-1";

      const typeBadge = document.createElement("span");
      typeBadge.className = diag.diagnosisType === "DEFINITIVO"
        ? "badge text-bg-primary"
        : "badge text-bg-warning text-dark";
      typeBadge.textContent = diag.diagnosisType;
      headerDiv.append(typeBadge);

      if (diag.code) {
        const codeBadge = document.createElement("span");
        codeBadge.className = "badge text-bg-light border pc-data-mono";
        codeBadge.textContent = diag.code;
        headerDiv.append(codeBadge);
      }

      infoCol.append(headerDiv);

      const descP = document.createElement("p");
      descP.className = "mb-1 fw-semibold text-break";
      descP.textContent = diag.description;
      infoCol.append(descP);

      if (diag.notes) {
        const notesP = document.createElement("p");
        notesP.className = "mb-0 small text-secondary text-break";
        notesP.textContent = diag.notes;
        infoCol.append(notesP);
      }

      item.append(infoCol);

      if (isEditable) {
        const actionCol = document.createElement("div");
        actionCol.className = "flex-shrink-0";
        const editBtn = document.createElement("button");
        editBtn.type = "button";
        editBtn.className = "btn btn-sm btn-outline-secondary";
        editBtn.textContent = "Editar";
        editBtn.dataset.action = "edit-diagnosis";
        editBtn.dataset.diagnosisId = diag.id;
        editBtn.addEventListener("click", () => handleOpenEditDiagnosis(diag));
        actionCol.append(editBtn);
        item.append(actionCol);
      }

      elements.diagnosesList.append(item);
    }
  }

  function handleOpenAddDiagnosis() {
    if (!canUpdate || currentConsultation?.status !== "BORRADOR") return;
    resetDiagnosisAlerts();
    clearDiagnosisValidationErrors();
    editingDiagnosisId = null;
    if (elements.diagnosisFormTitle) elements.diagnosisFormTitle.textContent = "Nuevo diagnóstico";
    if (elements.diagnosisIdInput) elements.diagnosisIdInput.value = "";
    if (elements.diagnosisTypeSelect) elements.diagnosisTypeSelect.value = "PRESUNTIVO";
    if (elements.diagnosisCodeInput) elements.diagnosisCodeInput.value = "";
    if (elements.diagnosisDescriptionInput) elements.diagnosisDescriptionInput.value = "";
    if (elements.diagnosisDescriptionCounter) elements.diagnosisDescriptionCounter.textContent = "0 / 300";
    if (elements.diagnosisNotesInput) elements.diagnosisNotesInput.value = "";
    if (elements.diagnosisForm) elements.diagnosisForm.hidden = false;
    updateFinalizeButtonState();
    elements.diagnosisDescriptionInput?.focus({ preventScroll: true });
  }

  function handleOpenEditDiagnosis(diag) {
    if (!canUpdate || currentConsultation?.status !== "BORRADOR") return;
    resetDiagnosisAlerts();
    clearDiagnosisValidationErrors();
    editingDiagnosisId = diag.id;
    if (elements.diagnosisFormTitle) elements.diagnosisFormTitle.textContent = "Editar diagnóstico";
    if (elements.diagnosisIdInput) elements.diagnosisIdInput.value = diag.id;
    if (elements.diagnosisTypeSelect) elements.diagnosisTypeSelect.value = diag.diagnosisType || "PRESUNTIVO";
    if (elements.diagnosisCodeInput) elements.diagnosisCodeInput.value = diag.code || "";
    if (elements.diagnosisDescriptionInput) {
      elements.diagnosisDescriptionInput.value = diag.description || "";
      if (elements.diagnosisDescriptionCounter) {
        elements.diagnosisDescriptionCounter.textContent = `${(diag.description || "").length} / 300`;
      }
    }
    if (elements.diagnosisNotesInput) elements.diagnosisNotesInput.value = diag.notes || "";
    if (elements.diagnosisForm) elements.diagnosisForm.hidden = false;
    updateFinalizeButtonState();
    elements.diagnosisDescriptionInput?.focus({ preventScroll: true });
  }

  function handleCloseDiagnosisForm() {
    resetDiagnosisForm();
    resetDiagnosisAlerts();
  }

  function resetDiagnosisForm() {
    editingDiagnosisId = null;
    if (elements.diagnosisIdInput) elements.diagnosisIdInput.value = "";
    if (elements.diagnosisTypeSelect) elements.diagnosisTypeSelect.value = "PRESUNTIVO";
    if (elements.diagnosisCodeInput) elements.diagnosisCodeInput.value = "";
    if (elements.diagnosisDescriptionInput) elements.diagnosisDescriptionInput.value = "";
    if (elements.diagnosisDescriptionCounter) elements.diagnosisDescriptionCounter.textContent = "0 / 300";
    if (elements.diagnosisNotesInput) elements.diagnosisNotesInput.value = "";
    if (elements.diagnosisForm) elements.diagnosisForm.hidden = true;
    clearDiagnosisValidationErrors();
    updateFinalizeButtonState();
  }

  function updateDiagnosisDescriptionCounter() {
    const len = elements.diagnosisDescriptionInput?.value.length ?? 0;
    if (elements.diagnosisDescriptionCounter) {
      elements.diagnosisDescriptionCounter.textContent = `${len} / 300`;
    }
  }

  async function handleDiagnosisSubmit(event) {
    event.preventDefault();
    if (!currentConsultationId || !currentConsultation || isSavingDiagnosis) return;
    if (!canUpdate || currentConsultation.status !== "BORRADOR") return;

    resetDiagnosisAlerts();
    clearDiagnosisValidationErrors();

    const descVal = elements.diagnosisDescriptionInput?.value.trim() ?? "";
    const typeVal = elements.diagnosisTypeSelect?.value.trim() ?? "";
    const codeVal = elements.diagnosisCodeInput?.value.trim() ?? "";
    const notesVal = elements.diagnosisNotesInput?.value.trim() ?? "";

    if (!descVal) {
      elements.diagnosisDescriptionInput?.setAttribute("aria-invalid", "true");
      showDiagnosisValidation("La descripción del diagnóstico es obligatoria.");
      elements.diagnosisDescriptionInput?.focus({ preventScroll: true });
      return;
    }
    if (descVal.length > 300) {
      elements.diagnosisDescriptionInput?.setAttribute("aria-invalid", "true");
      showDiagnosisValidation("La descripción no debe exceder los 300 caracteres.");
      elements.diagnosisDescriptionInput?.focus({ preventScroll: true });
      return;
    }
    if (!typeVal || (typeVal !== "PRESUNTIVO" && typeVal !== "DEFINITIVO")) {
      elements.diagnosisTypeSelect?.setAttribute("aria-invalid", "true");
      showDiagnosisValidation("El tipo de diagnóstico debe ser PRESUNTIVO o DEFINITIVO.");
      elements.diagnosisTypeSelect?.focus({ preventScroll: true });
      return;
    }
    if (codeVal.length > 50) {
      elements.diagnosisCodeInput?.setAttribute("aria-invalid", "true");
      showDiagnosisValidation("El código no debe exceder los 50 caracteres.");
      elements.diagnosisCodeInput?.focus({ preventScroll: true });
      return;
    }
    if (notesVal.length > 2000) {
      elements.diagnosisNotesInput?.setAttribute("aria-invalid", "true");
      showDiagnosisValidation("Las notas no deben exceder los 2000 caracteres.");
      elements.diagnosisNotesInput?.focus({ preventScroll: true });
      return;
    }

    const payload = {
      description: descVal,
      diagnosisType: typeVal,
      code: codeVal || null,
      notes: notesVal || null
    };

    const submissionRequestId = activeRequestId;
    const targetConsultationId = currentConsultationId;
    const targetDiagnosisId = editingDiagnosisId;
    setSavingDiagnosisState(true);

    try {
      if (targetDiagnosisId) {
        await client.requestJson(
          `/api/v1/consultations/${encodeURIComponent(targetConsultationId)}/diagnoses/${encodeURIComponent(targetDiagnosisId)}`,
          {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
          }
        );
      } else {
        await client.requestJson(
          `/api/v1/consultations/${encodeURIComponent(targetConsultationId)}/diagnoses`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
          }
        );
      }

      if (submissionRequestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }

      resetDiagnosisForm();
      showDiagnosisNotice(targetDiagnosisId ? "Diagnóstico actualizado correctamente en borrador." : "Diagnóstico registrado correctamente en borrador.");
      await loadDiagnoses(targetConsultationId, submissionRequestId);
      elements.diagnosisNotice?.focus({ preventScroll: true });
    } catch (error) {
      if (submissionRequestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }

      const message = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible guardar el diagnóstico.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";

      if (error instanceof ApiRequestError && error.status === 409) {
        if (currentConsultation) {
          currentConsultation.status = "CONFLICTO";
        }
        setFormEditable(false);
        showDiagnosisError(message, correlationSuffix);
        elements.diagnosisError?.focus({ preventScroll: true });
        return;
      }

      showDiagnosisError(message, correlationSuffix);
      elements.diagnosisError?.focus({ preventScroll: true });
    } finally {
      if (submissionRequestId === activeRequestId && targetConsultationId === currentConsultationId) {
        setSavingDiagnosisState(false);
      }
    }
  }

  function setSavingDiagnosisState(saving) {
    isSavingDiagnosis = saving;
    if (elements.diagnosisSaveButton) elements.diagnosisSaveButton.disabled = saving;
    if (elements.diagnosisSaveSpinner) elements.diagnosisSaveSpinner.hidden = !saving;
    if (elements.diagnosisCancelButton) elements.diagnosisCancelButton.disabled = saving;
    if (elements.diagnosisSaveLabel) {
      elements.diagnosisSaveLabel.textContent = saving ? "Guardando..." : "Guardar diagnóstico";
    }
    updateFinalizeButtonState();
  }

  function resetDiagnosisAlerts() {
    if (elements.diagnosisNotice) elements.diagnosisNotice.hidden = true;
    if (elements.diagnosisError) elements.diagnosisError.hidden = true;
    if (elements.diagnosisValidation) elements.diagnosisValidation.hidden = true;
    if (elements.diagnosesLoadError) elements.diagnosesLoadError.hidden = true;
  }

  function clearDiagnosisValidationErrors() {
    elements.diagnosisDescriptionInput?.removeAttribute("aria-invalid");
    elements.diagnosisTypeSelect?.removeAttribute("aria-invalid");
    elements.diagnosisCodeInput?.removeAttribute("aria-invalid");
    elements.diagnosisNotesInput?.removeAttribute("aria-invalid");
  }

  function showDiagnosisNotice(message) {
    if (elements.diagnosisNotice) {
      elements.diagnosisNotice.textContent = message;
      elements.diagnosisNotice.hidden = false;
    }
  }

  function showDiagnosisError(message, correlation) {
    if (elements.diagnosisErrorMessage) elements.diagnosisErrorMessage.textContent = message;
    if (elements.diagnosisErrorCorrelation) {
      elements.diagnosisErrorCorrelation.textContent = correlation;
      elements.diagnosisErrorCorrelation.hidden = !correlation;
    }
    if (elements.diagnosisError) elements.diagnosisError.hidden = false;
  }

  function showDiagnosisValidation(message) {
    if (elements.diagnosisValidationMessage) elements.diagnosisValidationMessage.textContent = message;
    if (elements.diagnosisValidation) elements.diagnosisValidation.hidden = false;
  }

  // ==========================================
  // HU-023: PRESCRIPCIONES MÉDICAS EXTERNAS
  // ==========================================

  async function loadPrescriptions(consultationId, requestId) {
    if (!elements.prescriptionsSection) return;
    if (elements.prescriptionsLoading) elements.prescriptionsLoading.hidden = false;
    if (elements.prescriptionsList) elements.prescriptionsList.hidden = true;
    if (elements.prescriptionsEmpty) elements.prescriptionsEmpty.hidden = true;
    if (elements.prescriptionsLoadError) elements.prescriptionsLoadError.hidden = true;

    try {
      const list = await client.requestJson(`/api/v1/consultations/${encodeURIComponent(consultationId)}/prescriptions`);
      if (requestId !== activeRequestId || consultationId !== currentConsultationId) return;

      if (!Array.isArray(list)) {
        throw new ApiRequestError({
          kind: "INVALID_RESPONSE",
          code: "INVALID_RESPONSE",
          message: "La respuesta de prescripciones no tiene un formato de lista válido."
        });
      }

      currentPrescriptions = list;
      renderPrescriptions(currentPrescriptions);
      const isEditable = canUpdate && currentConsultation?.status === "BORRADOR";
      const isVetAssigned = !currentConsultation?.veterinarianId || !currentUserId || currentConsultation.veterinarianId === currentUserId;
      if (elements.prescriptionAddButton) {
        elements.prescriptionAddButton.hidden = !(isEditable && isVetAssigned);
      }
    } catch (err) {
      if (requestId !== activeRequestId || consultationId !== currentConsultationId) return;
      currentPrescriptions = [];
      if (elements.prescriptionsList) {
        elements.prescriptionsList.replaceChildren();
        elements.prescriptionsList.hidden = true;
      }
      if (elements.prescriptionsEmpty) elements.prescriptionsEmpty.hidden = true;
      if (elements.prescriptionsCounter) elements.prescriptionsCounter.textContent = "0";

      if (elements.prescriptionsLoadError) {
        elements.prescriptionsLoadError.hidden = false;
        if (elements.prescriptionsLoadErrorMessage) {
          elements.prescriptionsLoadErrorMessage.textContent =
            err instanceof ApiRequestError
              ? err.message
              : (err?.message || "No fue posible consultar las prescripciones de la consulta.");
        }
        if (elements.prescriptionsLoadErrorCorrelation) {
          const correlationId = err instanceof ApiRequestError ? err.correlationId : err?.correlationId;
          elements.prescriptionsLoadErrorCorrelation.textContent = correlationId
            ? `Código de seguimiento: ${correlationId}`
            : "";
          elements.prescriptionsLoadErrorCorrelation.hidden = !correlationId;
        }
        elements.prescriptionsLoadError.focus?.({ preventScroll: true });
      }
    } finally {
      if (requestId === activeRequestId && consultationId === currentConsultationId) {
        if (elements.prescriptionsLoading) elements.prescriptionsLoading.hidden = true;
      }
    }
  }

  function renderPrescriptions(list) {
    if (!elements.prescriptionsList) return;
    if (elements.prescriptionsLoadError) elements.prescriptionsLoadError.hidden = true;
    elements.prescriptionsList.replaceChildren();
    if (elements.prescriptionsCounter) {
      elements.prescriptionsCounter.textContent = String(list.length);
    }
    if (list.length === 0) {
      if (elements.prescriptionsEmpty) elements.prescriptionsEmpty.hidden = false;
      elements.prescriptionsList.hidden = true;
      return;
    }
    if (elements.prescriptionsEmpty) elements.prescriptionsEmpty.hidden = true;
    elements.prescriptionsList.hidden = false;

    for (const presc of list) {
      const card = document.createElement("div");
      card.className = "card border rounded p-3 bg-white mb-2 pc-prescription-card";
      card.dataset.prescriptionId = presc.id;

      // Header
      const header = document.createElement("div");
      header.className = "d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2 pb-2 border-bottom";

      const headerBadges = document.createElement("div");
      headerBadges.className = "d-flex flex-wrap align-items-center gap-2";

      const statusBadge = document.createElement("span");
      statusBadge.className = "badge text-bg-success";
      statusBadge.textContent = presc.status || "EMITIDA";
      headerBadges.append(statusBadge);

      const folioBadge = document.createElement("span");
      folioBadge.className = "badge text-bg-light border pc-data-mono";
      folioBadge.textContent = `Folio: ${(presc.id || "").substring(0, 8)}`;
      headerBadges.append(folioBadge);

      if (presc.issuedAt) {
        const dateSpan = document.createElement("span");
        dateSpan.className = "small text-secondary";
        try {
          const date = new Date(presc.issuedAt);
          dateSpan.textContent = date.toLocaleString();
        } catch {
          dateSpan.textContent = presc.issuedAt;
        }
        headerBadges.append(dateSpan);
      }
      header.append(headerBadges);

      const vetSpan = document.createElement("div");
      vetSpan.className = "small text-secondary";
      vetSpan.textContent = "Emitida por veterinario asignado";
      header.append(vetSpan);

      card.append(header);

      // Items Table
      if (Array.isArray(presc.items) && presc.items.length > 0) {
        const tableResponsive = document.createElement("div");
        tableResponsive.className = "table-responsive mb-2";

        const table = document.createElement("table");
        table.className = "table table-sm table-bordered align-middle mb-0 small";

        const thead = document.createElement("thead");
        thead.className = "table-light";
        const trHead = document.createElement("tr");

        const thMed = document.createElement("th");
        thMed.scope = "col";
        thMed.textContent = "Medicamento";
        trHead.append(thMed);

        const thDose = document.createElement("th");
        thDose.scope = "col";
        thDose.textContent = "Dosis";
        trHead.append(thDose);

        const thFreq = document.createElement("th");
        thFreq.scope = "col";
        thFreq.textContent = "Frecuencia";
        trHead.append(thFreq);

        const thDur = document.createElement("th");
        thDur.scope = "col";
        thDur.textContent = "Duración";
        trHead.append(thDur);

        const thRoute = document.createElement("th");
        thRoute.scope = "col";
        thRoute.textContent = "Vía";
        trHead.append(thRoute);

        const thInst = document.createElement("th");
        thInst.scope = "col";
        thInst.textContent = "Indicaciones específicas";
        trHead.append(thInst);

        thead.append(trHead);
        table.append(thead);

        const tbody = document.createElement("tbody");
        for (const item of presc.items) {
          const tr = document.createElement("tr");

          const tdMed = document.createElement("td");
          tdMed.className = "fw-semibold text-break";
          tdMed.textContent = item.medicationName;
          tr.append(tdMed);

          const tdDose = document.createElement("td");
          tdDose.textContent = item.dosage;
          tr.append(tdDose);

          const tdFreq = document.createElement("td");
          tdFreq.textContent = item.frequency;
          tr.append(tdFreq);

          const tdDur = document.createElement("td");
          tdDur.textContent = item.duration;
          tr.append(tdDur);

          const tdRoute = document.createElement("td");
          tdRoute.textContent = item.route || "—";
          tr.append(tdRoute);

          const tdInst = document.createElement("td");
          tdInst.className = "text-secondary text-break";
          tdInst.textContent = item.instructions || item.specificInstructions || "—";
          tr.append(tdInst);

          tbody.append(tr);
        }
        table.append(tbody);
        tableResponsive.append(table);
        card.append(tableResponsive);
      }

      // General Instructions
      if (presc.generalInstructions) {
        const genInstDiv = document.createElement("div");
        genInstDiv.className = "p-2 rounded bg-light border mb-2 small";
        const genInstTitle = document.createElement("strong");
        genInstTitle.className = "d-block text-dark mb-1";
        genInstTitle.textContent = "Indicaciones generales:";
        genInstDiv.append(genInstTitle);
        const genInstText = document.createElement("span");
        genInstText.className = "text-secondary text-break";
        genInstText.textContent = presc.generalInstructions;
        genInstDiv.append(genInstText);
        card.append(genInstDiv);
      }

      // Footer
      const footer = document.createElement("div");
      footer.className = "d-flex align-items-center justify-content-between pt-2 border-top small text-secondary";
      const footerDisclaimer = document.createElement("span");
      footerDisclaimer.textContent = "Medicamentos externos al catálogo";
      const footerBadge = document.createElement("span");
      footerBadge.className = "badge text-bg-light border";
      footerBadge.textContent = "Sin dispensación de almacén";
      footer.append(footerDisclaimer, footerBadge);
      card.append(footer);

      elements.prescriptionsList.append(card);
    }
  }

  function handleOpenPrescriptionForm() {
    if (!canUpdate || currentConsultation?.status !== "BORRADOR") return;
    resetPrescriptionAlerts();
    clearPrescriptionValidationErrors();
    currentPrescriptionIdempotencyKey = (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function")
      ? crypto.randomUUID()
      : `presc-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;

    if (elements.prescriptionGeneralInstructions) {
      elements.prescriptionGeneralInstructions.value = "";
    }
    if (elements.prescriptionInstructionsCounter) {
      elements.prescriptionInstructionsCounter.textContent = "0 / 2000";
    }
    if (elements.prescriptionItemsContainer) {
      elements.prescriptionItemsContainer.replaceChildren();
      addPrescriptionItemRow();
    }
    if (elements.prescriptionForm) {
      elements.prescriptionForm.hidden = false;
    }
    updateFinalizeButtonState();
    const firstInput = elements.prescriptionItemsContainer?.querySelector(".pc-prescription-item-name");
    firstInput?.focus({ preventScroll: true });
  }

  function handleClosePrescriptionForm() {
    resetPrescriptionForm();
    resetPrescriptionAlerts();
  }

  function resetPrescriptionForm() {
    currentPrescriptionIdempotencyKey = null;
    if (elements.prescriptionGeneralInstructions) {
      elements.prescriptionGeneralInstructions.value = "";
    }
    if (elements.prescriptionInstructionsCounter) {
      elements.prescriptionInstructionsCounter.textContent = "0 / 2000";
    }
    if (elements.prescriptionItemsContainer) {
      elements.prescriptionItemsContainer.replaceChildren();
    }
    if (elements.prescriptionForm) {
      elements.prescriptionForm.hidden = true;
    }
    clearPrescriptionValidationErrors();
    updateFinalizeButtonState();
  }

  function addPrescriptionItemRow(initialData = {}) {
    if (!elements.prescriptionItemsContainer) return null;

    const row = document.createElement("div");
    row.className = "card border p-3 bg-white pc-prescription-item-row";

    // Top bar: badge & remove button
    const topBar = document.createElement("div");
    topBar.className = "d-flex justify-content-between align-items-center mb-2";

    const badge = document.createElement("span");
    badge.className = "badge text-bg-secondary-subtle text-secondary-emphasis border pc-prescription-item-badge";
    badge.textContent = "Medicamento";
    topBar.append(badge);

    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "btn btn-sm btn-outline-danger pc-prescription-item-remove-btn";
    removeBtn.textContent = "Eliminar";
    removeBtn.title = "Eliminar medicamento";
    removeBtn.setAttribute("aria-label", "Eliminar medicamento");
    removeBtn.addEventListener("click", () => {
      row.remove();
      updatePrescriptionItemRowsState();
    });
    topBar.append(removeBtn);
    row.append(topBar);

    // Form fields grid
    const grid = document.createElement("div");
    grid.className = "row g-2";

    // 1. Medication name
    const colName = document.createElement("div");
    colName.className = "col-12 col-md-6";
    const labelName = document.createElement("label");
    labelName.className = "form-label small fw-semibold mb-1";
    labelName.innerHTML = 'Medicamento <span class="text-danger">*</span>';
    const inputName = document.createElement("input");
    inputName.type = "text";
    inputName.className = "form-control form-control-sm pc-prescription-item-name";
    inputName.maxLength = 200;
    inputName.placeholder = "Ej: Amoxicilina + Ác. Clavulánico 250 mg";
    inputName.required = true;
    inputName.value = initialData.medicationName || "";
    colName.append(labelName, inputName);

    // 2. Dosage
    const colDose = document.createElement("div");
    colDose.className = "col-12 col-md-6";
    const labelDose = document.createElement("label");
    labelDose.className = "form-label small fw-semibold mb-1";
    labelDose.innerHTML = 'Dosis <span class="text-danger">*</span>';
    const inputDose = document.createElement("input");
    inputDose.type = "text";
    inputDose.className = "form-control form-control-sm pc-prescription-item-dosage";
    inputDose.maxLength = 100;
    inputDose.placeholder = "Ej: 1 tableta";
    inputDose.required = true;
    inputDose.value = initialData.dosage || "";
    colDose.append(labelDose, inputDose);

    // 3. Frequency
    const colFreq = document.createElement("div");
    colFreq.className = "col-6 col-md-4";
    const labelFreq = document.createElement("label");
    labelFreq.className = "form-label small fw-semibold mb-1";
    labelFreq.innerHTML = 'Frecuencia <span class="text-danger">*</span>';
    const inputFreq = document.createElement("input");
    inputFreq.type = "text";
    inputFreq.className = "form-control form-control-sm pc-prescription-item-frequency";
    inputFreq.maxLength = 100;
    inputFreq.placeholder = "Ej: Cada 12 horas";
    inputFreq.required = true;
    inputFreq.value = initialData.frequency || "";
    colFreq.append(labelFreq, inputFreq);

    // 4. Duration
    const colDur = document.createElement("div");
    colDur.className = "col-6 col-md-4";
    const labelDur = document.createElement("label");
    labelDur.className = "form-label small fw-semibold mb-1";
    labelDur.innerHTML = 'Duración <span class="text-danger">*</span>';
    const inputDur = document.createElement("input");
    inputDur.type = "text";
    inputDur.className = "form-control form-control-sm pc-prescription-item-duration";
    inputDur.maxLength = 100;
    inputDur.placeholder = "Ej: 7 días";
    inputDur.required = true;
    inputDur.value = initialData.duration || "";
    colDur.append(labelDur, inputDur);

    // 5. Route
    const colRoute = document.createElement("div");
    colRoute.className = "col-12 col-md-4";
    const labelRoute = document.createElement("label");
    labelRoute.className = "form-label small fw-semibold mb-1";
    labelRoute.innerHTML = 'Vía <span class="text-secondary fw-normal">(opcional)</span>';
    const inputRoute = document.createElement("input");
    inputRoute.type = "text";
    inputRoute.className = "form-control form-control-sm pc-prescription-item-route";
    inputRoute.maxLength = 50;
    inputRoute.placeholder = "Ej: Oral, Subcutánea";
    inputRoute.value = initialData.route || "";
    colRoute.append(labelRoute, inputRoute);

    // 6. Specific instructions
    const colInst = document.createElement("div");
    colInst.className = "col-12";
    const labelInst = document.createElement("label");
    labelInst.className = "form-label small fw-semibold mb-1";
    labelInst.innerHTML = 'Indicaciones específicas <span class="text-secondary fw-normal">(opcional)</span>';
    const inputInst = document.createElement("input");
    inputInst.type = "text";
    inputInst.className = "form-control form-control-sm pc-prescription-item-instructions";
    inputInst.maxLength = 2000;
    inputInst.placeholder = "Ej: Administrar con alimento";
    inputInst.value = initialData.instructions || initialData.specificInstructions || "";
    colInst.append(labelInst, inputInst);

    grid.append(colName, colDose, colFreq, colDur, colRoute, colInst);
    row.append(grid);

    elements.prescriptionItemsContainer.append(row);
    updatePrescriptionItemRowsState();
    return row;
  }

  function updatePrescriptionItemRowsState() {
    if (!elements.prescriptionItemsContainer) return;
    const rows = elements.prescriptionItemsContainer.querySelectorAll(".pc-prescription-item-row");
    const count = rows.length;
    rows.forEach((row, index) => {
      const badge = row.querySelector(".pc-prescription-item-badge");
      if (badge) {
        badge.textContent = `Medicamento #${index + 1}`;
      }
      const removeBtn = row.querySelector(".pc-prescription-item-remove-btn");
      if (removeBtn) {
        removeBtn.disabled = count <= 1;
      }
    });
  }

  function handleAddPrescriptionItemRow() {
    const row = addPrescriptionItemRow();
    const nameInput = row?.querySelector(".pc-prescription-item-name");
    nameInput?.focus({ preventScroll: true });
  }

  function updatePrescriptionInstructionsCounter() {
    const len = elements.prescriptionGeneralInstructions?.value.length ?? 0;
    if (elements.prescriptionInstructionsCounter) {
      elements.prescriptionInstructionsCounter.textContent = `${len} / 2000`;
    }
  }

  function handleRetryPrescriptions() {
    if (currentConsultationId) {
      loadPrescriptions(currentConsultationId, activeRequestId);
    }
  }

  async function handlePrescriptionSubmit(event) {
    event.preventDefault();
    if (!currentConsultationId || !currentConsultation || isSavingPrescription) return;
    if (!canUpdate || currentConsultation.status !== "BORRADOR") return;

    resetPrescriptionAlerts();
    clearPrescriptionValidationErrors();

    const rows = elements.prescriptionItemsContainer?.querySelectorAll(".pc-prescription-item-row") || [];
    if (rows.length === 0) {
      showPrescriptionValidation("Debe registrar al menos un medicamento en la prescripción.");
      return;
    }

    const items = [];
    let firstInvalidInput = null;

    rows.forEach((row) => {
      const nameInput = row.querySelector(".pc-prescription-item-name");
      const doseInput = row.querySelector(".pc-prescription-item-dosage");
      const freqInput = row.querySelector(".pc-prescription-item-frequency");
      const durInput = row.querySelector(".pc-prescription-item-duration");
      const routeInput = row.querySelector(".pc-prescription-item-route");
      const instInput = row.querySelector(".pc-prescription-item-instructions");

      const nameVal = nameInput?.value.trim() ?? "";
      const doseVal = doseInput?.value.trim() ?? "";
      const freqVal = freqInput?.value.trim() ?? "";
      const durVal = durInput?.value.trim() ?? "";
      const routeVal = routeInput?.value.trim() ?? "";
      const instVal = instInput?.value.trim() ?? "";

      let rowHasError = false;

      if (!nameVal || nameVal.length > 200) {
        nameInput?.setAttribute("aria-invalid", "true");
        rowHasError = true;
        if (!firstInvalidInput) firstInvalidInput = nameInput;
      }
      if (!doseVal || doseVal.length > 100) {
        doseInput?.setAttribute("aria-invalid", "true");
        rowHasError = true;
        if (!firstInvalidInput) firstInvalidInput = doseInput;
      }
      if (!freqVal || freqVal.length > 100) {
        freqInput?.setAttribute("aria-invalid", "true");
        rowHasError = true;
        if (!firstInvalidInput) firstInvalidInput = freqInput;
      }
      if (!durVal || durVal.length > 100) {
        durInput?.setAttribute("aria-invalid", "true");
        rowHasError = true;
        if (!firstInvalidInput) firstInvalidInput = durInput;
      }
      if (routeVal.length > 50) {
        routeInput?.setAttribute("aria-invalid", "true");
        rowHasError = true;
        if (!firstInvalidInput) firstInvalidInput = routeInput;
      }
      if (instVal.length > 2000) {
        instInput?.setAttribute("aria-invalid", "true");
        rowHasError = true;
        if (!firstInvalidInput) firstInvalidInput = instInput;
      }

      if (!rowHasError) {
        items.push({
          medicationName: nameVal,
          dosage: doseVal,
          frequency: freqVal,
          duration: durVal,
          route: routeVal || null,
          instructions: instVal || null
        });
      }
    });

    const genInstVal = elements.prescriptionGeneralInstructions?.value.trim() ?? "";
    if (genInstVal.length > 2000) {
      elements.prescriptionGeneralInstructions?.setAttribute("aria-invalid", "true");
      if (!firstInvalidInput) firstInvalidInput = elements.prescriptionGeneralInstructions;
      showPrescriptionValidation("Las indicaciones generales no deben exceder los 2000 caracteres.");
      firstInvalidInput?.focus({ preventScroll: true });
      return;
    }

    if (firstInvalidInput || items.length !== rows.length) {
      showPrescriptionValidation("Por favor completa todos los campos obligatorios de cada medicamento.");
      firstInvalidInput?.focus({ preventScroll: true });
      return;
    }

    const payload = {
      idempotencyKey: currentPrescriptionIdempotencyKey,
      generalInstructions: genInstVal || null,
      items
    };

    const submissionRequestId = activeRequestId;
    const targetConsultationId = currentConsultationId;
    setSavingPrescriptionState(true);

    try {
      await client.requestJson(
        `/api/v1/consultations/${encodeURIComponent(targetConsultationId)}/prescriptions`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        }
      );

      if (submissionRequestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }

      resetPrescriptionForm();
      showPrescriptionNotice("Prescripción médica emitida correctamente.");
      await loadPrescriptions(targetConsultationId, submissionRequestId);
      elements.prescriptionNotice?.focus({ preventScroll: true });
    } catch (error) {
      if (submissionRequestId !== activeRequestId || targetConsultationId !== currentConsultationId) {
        return;
      }

      const message = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible emitir la prescripción médica.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";

      if (error instanceof ApiRequestError && error.status === 409) {
        if (error.code === "INVALID_CONSULTATION_STATUS") {
          if (currentConsultation) {
            currentConsultation.status = "CONFLICTO";
          }
          setFormEditable(false);
        }
        showPrescriptionError(message, correlationSuffix);
        elements.prescriptionError?.focus({ preventScroll: true });
        return;
      }

      showPrescriptionError(message, correlationSuffix);
      elements.prescriptionError?.focus({ preventScroll: true });
    } finally {
      if (submissionRequestId === activeRequestId && targetConsultationId === currentConsultationId) {
        setSavingPrescriptionState(false);
      }
    }
  }

  function setSavingPrescriptionState(saving) {
    isSavingPrescription = saving;
    if (elements.prescriptionSubmitButton) elements.prescriptionSubmitButton.disabled = saving;
    if (elements.prescriptionSubmitSpinner) elements.prescriptionSubmitSpinner.hidden = !saving;
    if (elements.prescriptionCancelButton) elements.prescriptionCancelButton.disabled = saving;
    if (elements.prescriptionAddItemRowButton) elements.prescriptionAddItemRowButton.disabled = saving;
    if (elements.prescriptionSubmitLabel) {
      elements.prescriptionSubmitLabel.textContent = saving ? "Emitiendo..." : "Emitir prescripción";
    }
    updateFinalizeButtonState();
  }

  function resetPrescriptionAlerts() {
    if (elements.prescriptionNotice) elements.prescriptionNotice.hidden = true;
    if (elements.prescriptionError) elements.prescriptionError.hidden = true;
    if (elements.prescriptionValidation) elements.prescriptionValidation.hidden = true;
    if (elements.prescriptionsLoadError) elements.prescriptionsLoadError.hidden = true;
  }

  function clearPrescriptionValidationErrors() {
    const rows = elements.prescriptionItemsContainer?.querySelectorAll(".pc-prescription-item-row") || [];
    rows.forEach(row => {
      row.querySelectorAll("input").forEach(input => input.removeAttribute("aria-invalid"));
    });
    elements.prescriptionGeneralInstructions?.removeAttribute("aria-invalid");
    if (elements.prescriptionValidation) elements.prescriptionValidation.hidden = true;
  }

  function showPrescriptionNotice(message) {
    if (elements.prescriptionNotice) {
      elements.prescriptionNotice.textContent = message;
      elements.prescriptionNotice.hidden = false;
    }
  }

  function showPrescriptionError(message, correlation) {
    if (elements.prescriptionErrorMessage) elements.prescriptionErrorMessage.textContent = message;
    if (elements.prescriptionErrorCorrelation) {
      elements.prescriptionErrorCorrelation.textContent = correlation;
      elements.prescriptionErrorCorrelation.hidden = !correlation;
    }
    if (elements.prescriptionError) elements.prescriptionError.hidden = false;
  }

  function showPrescriptionValidation(message) {
    if (elements.prescriptionValidationMessage) elements.prescriptionValidationMessage.textContent = message;
    if (elements.prescriptionValidation) elements.prescriptionValidation.hidden = false;
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
    finalizeButton: document.querySelector("#evaluation-finalize-button"),
    finalizeLabel: document.querySelector("#evaluation-finalize-label"),
    finalizeSpinner: document.querySelector("#evaluation-finalize-spinner"),
    finalizeDialog: document.querySelector("#evaluation-finalize-dialog"),
    finalizeDialogError: document.querySelector("#evaluation-finalize-dialog-error"),
    finalizeDialogErrorMessage: document.querySelector("#evaluation-finalize-dialog-error-message"),
    finalizeCancelButton: document.querySelector("#evaluation-finalize-cancel-button"),
    finalizeConfirmButton: document.querySelector("#evaluation-finalize-confirm-button"),
    finalizeConfirmLabel: document.querySelector("#evaluation-finalize-confirm-label"),
    finalizeConfirmSpinner: document.querySelector("#evaluation-finalize-confirm-spinner"),
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
    observationsInput: document.querySelector("#evaluation-observations"),
    diagnosesSection: document.querySelector("#evaluation-diagnoses-section"),
    diagnosesCounter: document.querySelector("#evaluation-diagnoses-counter"),
    diagnosesList: document.querySelector("#evaluation-diagnoses-list"),
    diagnosesLoading: document.querySelector("#evaluation-diagnoses-loading"),
    diagnosesLoadError: document.querySelector("#evaluation-diagnoses-load-error"),
    diagnosesLoadErrorMessage: document.querySelector("#evaluation-diagnoses-load-error-message"),
    diagnosesLoadErrorCorrelation: document.querySelector("#evaluation-diagnoses-load-error-correlation"),
    diagnosesRetryButton: document.querySelector("#evaluation-diagnoses-retry-button"),
    diagnosesEmpty: document.querySelector("#evaluation-diagnoses-empty"),
    diagnosisAddButton: document.querySelector("#diagnosis-add-button"),
    diagnosisForm: document.querySelector("#diagnosis-form"),
    diagnosisFormTitle: document.querySelector("#diagnosis-form-title"),
    diagnosisIdInput: document.querySelector("#diagnosis-id"),
    diagnosisTypeSelect: document.querySelector("#diagnosis-type"),
    diagnosisCodeInput: document.querySelector("#diagnosis-code"),
    diagnosisDescriptionInput: document.querySelector("#diagnosis-description"),
    diagnosisDescriptionCounter: document.querySelector("#diagnosis-description-counter"),
    diagnosisNotesInput: document.querySelector("#diagnosis-notes"),
    diagnosisCancelButton: document.querySelector("#diagnosis-cancel-button"),
    diagnosisSaveButton: document.querySelector("#diagnosis-save-button"),
    diagnosisSaveLabel: document.querySelector("#diagnosis-save-label"),
    diagnosisSaveSpinner: document.querySelector("#diagnosis-save-spinner"),
    diagnosisNotice: document.querySelector("#diagnosis-notice"),
    diagnosisError: document.querySelector("#diagnosis-error"),
    diagnosisErrorMessage: document.querySelector("#diagnosis-error-message"),
    diagnosisErrorCorrelation: document.querySelector("#diagnosis-error-correlation"),
    diagnosisValidation: document.querySelector("#diagnosis-validation"),
    diagnosisValidationMessage: document.querySelector("#diagnosis-validation-message"),
    prescriptionsSection: document.querySelector("#evaluation-prescriptions-section"),
    prescriptionsCounter: document.querySelector("#evaluation-prescriptions-counter"),
    prescriptionAddButton: document.querySelector("#prescription-add-button"),
    prescriptionNotice: document.querySelector("#prescription-notice"),
    prescriptionError: document.querySelector("#prescription-error"),
    prescriptionErrorMessage: document.querySelector("#prescription-error-message"),
    prescriptionErrorCorrelation: document.querySelector("#prescription-error-correlation"),
    prescriptionValidation: document.querySelector("#prescription-validation"),
    prescriptionValidationMessage: document.querySelector("#prescription-validation-message"),
    prescriptionForm: document.querySelector("#prescription-form"),
    prescriptionItemsContainer: document.querySelector("#prescription-items-container"),
    prescriptionAddItemRowButton: document.querySelector("#prescription-add-item-row-button"),
    prescriptionGeneralInstructions: document.querySelector("#prescription-general-instructions"),
    prescriptionInstructionsCounter: document.querySelector("#prescription-instructions-counter"),
    prescriptionCancelButton: document.querySelector("#prescription-cancel-button"),
    prescriptionSubmitButton: document.querySelector("#prescription-submit-button"),
    prescriptionSubmitLabel: document.querySelector("#prescription-submit-label"),
    prescriptionSubmitSpinner: document.querySelector("#prescription-submit-spinner"),
    prescriptionsLoading: document.querySelector("#evaluation-prescriptions-loading"),
    prescriptionsLoadError: document.querySelector("#evaluation-prescriptions-load-error"),
    prescriptionsLoadErrorMessage: document.querySelector("#evaluation-prescriptions-load-error-message"),
    prescriptionsLoadErrorCorrelation: document.querySelector("#evaluation-prescriptions-load-error-correlation"),
    prescriptionsRetryButton: document.querySelector("#evaluation-prescriptions-retry-button"),
    prescriptionsEmpty: document.querySelector("#evaluation-prescriptions-empty"),
    prescriptionsList: document.querySelector("#evaluation-prescriptions-list")
  };
}
