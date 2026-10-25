import { ApiRequestError } from "./api-client.js";

const PATIENT_CREATE = "PATIENT_CREATE";
const PATIENT_READ = "PATIENT_READ";
const PATIENT_UPDATE = "PATIENT_UPDATE";
const APPOINTMENT_CREATE = "APPOINTMENT_CREATE";
const CONSULTATION_READ = "CONSULTATION_READ";

const SPECIES_LABELS = Object.freeze({
  CANINO: "Canino",
  FELINO: "Felino",
  AVE: "Ave",
  EXOTICO: "Exótico",
  OTRO: "Otro"
});

const SEX_LABELS = Object.freeze({
  MACHO: "Macho",
  HEMBRA: "Hembra",
  NO_ESPECIFICADO: "No especificado"
});

const STATUS_META = Object.freeze({
  ACTIVO: { label: "Activo", badgeClass: "badge text-bg-success-subtle text-success-emphasis border border-success-subtle" },
  INACTIVO: { label: "Inactivo", badgeClass: "badge text-bg-secondary-subtle text-secondary-emphasis border border-secondary-subtle" },
  FALLECIDO: { label: "Fallecido", badgeClass: "badge text-bg-danger-subtle text-danger-emphasis border border-danger-subtle" }
});

export function createPatientsController({ document, client, onCreateAppointment = null }) {
  const e = elements(document);
  const s = searchElements(document);
  const p = profileElements(document);
  let canCreate = false;
  let canRead = false;
  let canUpdate = false;
  let canCreateAppointment = false;
  let canReadConsultations = false;
  let loading = false;
  let searching = false;
  let loadingProfile = false;
  let page = 0;
  let totalPages = 0;
  let activeProfileId = null;
  let currentProfile = null;
  let editingPatientId = null;
  let currentSessionId = 0;
  let activeProfileRequestId = 0;

  e.open.addEventListener("click", openCreateDialog);
  e.cancel.addEventListener("click", () => {
    editingPatientId = null;
    e.dialog.close();
  });
  e.ownerSearch.addEventListener("click", findOwners);
  e.form.addEventListener("submit", save);

  if (s) {
    s.navButton.addEventListener("click", openSearchView);
    s.backButton.addEventListener("click", closeSearchView);
    s.newPatientViewButton?.addEventListener("click", openCreateDialog);
    s.searchForm.addEventListener("submit", submitSearch);
    s.clearButton.addEventListener("click", clearFilters);
    s.retryButton.addEventListener("click", () => executeSearch(page));
    s.previousButton.addEventListener("click", () => executeSearch(page - 1));
    s.nextButton.addEventListener("click", () => executeSearch(page + 1));
  }

  if (p) {
    p.backButton.addEventListener("click", backToSearchResults);
    p.editButton?.addEventListener("click", openEditPatientDialog);
    p.newAppointmentButton?.addEventListener("click", () => {
      if (canCreateAppointment && currentProfile?.id && currentProfile?.status === "ACTIVO" && typeof onCreateAppointment === "function") {
        onCreateAppointment({
          id: currentProfile.id,
          name: currentProfile.name,
          ownerFullName: currentProfile.owner?.fullName || ""
        });
      }
    });
    p.retryButton.addEventListener("click", () => {
      if (activeProfileId) {
        openPatientProfile(activeProfileId);
      }
    });
  }

  return Object.freeze({
    sessionReady(identity) {
      currentSessionId++;
      activeProfileRequestId++;
      const authorities = new Set(identity?.authorities ?? []);
      canCreate = authorities.has(PATIENT_CREATE);
      canRead = authorities.has(PATIENT_READ);
      canUpdate = authorities.has(PATIENT_UPDATE);
      canCreateAppointment = authorities.has(APPOINTMENT_CREATE);
      canReadConsultations = authorities.has(CONSULTATION_READ);
      currentProfile = null;
      editingPatientId = null;
      clearPatientDiagnoses();
      e.open.hidden = !canCreate;
      if (s) {
        s.navButton.hidden = !canRead;
        if (s.newPatientViewButton) {
          s.newPatientViewButton.hidden = !canCreate;
        }
        closeSearchView();
        resetSearchState();
      }
      if (p) {
        p.view.hidden = true;
        if (p.editButton) {
          p.editButton.hidden = true;
        }
        if (p.notice) {
          p.notice.hidden = true;
        }
        p.newConsultationButton.hidden = true;
        p.newConsultationButton.disabled = true;
        p.newAppointmentButton.hidden = true;
        p.newAppointmentButton.disabled = true;
        p.newAppointmentButton.setAttribute("aria-disabled", "true");
        activeProfileId = null;
      }
    },
    sessionEnded() {
      currentSessionId++;
      activeProfileRequestId++;
      canCreate = false;
      canRead = false;
      canUpdate = false;
      canCreateAppointment = false;
      activeProfileId = null;
      currentProfile = null;
      editingPatientId = null;
      clearPatientDiagnoses();
      e.open.hidden = true;
      if (e.dialog.open) {
        e.dialog.close();
      }
      if (p) {
        p.view.hidden = true;
        if (p.editButton) {
          p.editButton.hidden = true;
        }
        if (p.notice) {
          p.notice.hidden = true;
        }
      }
      if (s) {
        s.navButton.hidden = true;
        if (s.newPatientViewButton) {
          s.newPatientViewButton.hidden = true;
        }
        s.view.hidden = true;
        s.home.hidden = false;
        resetSearchState();
      }
    },
    openSearchView,
    openPatientProfile,
    showProfileNotice(message) {
      if (p && !p.view.hidden && p.notice) {
        p.notice.textContent = message;
        p.notice.hidden = false;
      }
    }
  });

  function openSearchView() {
    if (!s) return;
    if (p) {
      p.view.hidden = true;
    }
    s.home.hidden = true;
    s.view.hidden = false;
    if (!canRead) {
      setSearchState("forbidden");
      s.forbidden.focus({ preventScroll: true });
      return;
    }
    if (s.rows.childElementCount === 0 && s.empty.hidden && s.error.hidden && s.validation.hidden) {
      setSearchState("initial");
    }
    s.title.focus({ preventScroll: true });
    s.queryInput.focus({ preventScroll: true });
  }

  function closeSearchView() {
    if (!s) return;
    if (p) {
      p.view.hidden = true;
    }
    s.view.hidden = true;
    s.home.hidden = false;
  }

  function backToSearchResults() {
    if (!p || !s) return;
    p.view.hidden = true;
    s.home.hidden = true;
    s.view.hidden = false;
    const trigger = activeProfileId
      ? s.view.querySelector(`[data-action="view-patient-profile"][data-patient-id="${CSS.escape(activeProfileId)}"]`)
      : null;
    (trigger ?? s.title).focus({ preventScroll: true });
  }

  function resetSearchState() {
    if (!s) return;
    page = 0;
    totalPages = 0;
    s.searchForm.reset();
    s.queryInput.removeAttribute("aria-invalid");
    s.rows.replaceChildren();
    s.cards.replaceChildren();
    setSearchState("initial");
  }

  function clearFilters() {
    if (!s) return;
    resetSearchState();
    s.queryInput.focus({ preventScroll: true });
  }

  function submitSearch(event) {
    event.preventDefault();
    if (!s) return;
    if (!canRead) {
      setSearchState("forbidden");
      return;
    }
    const trimmedQuery = s.queryInput.value.trim();
    if (trimmedQuery.length === 1) {
      s.queryInput.setAttribute("aria-invalid", "true");
      s.validation.textContent = "Ingresa al menos 2 caracteres para buscar por texto o deja el campo vacío para filtrar por especie o estado.";
      setSearchState("validation");
      s.queryInput.focus();
      return;
    }
    if (trimmedQuery.length > 120) {
      s.queryInput.setAttribute("aria-invalid", "true");
      s.validation.textContent = "El término de búsqueda no debe exceder 120 caracteres.";
      setSearchState("validation");
      s.queryInput.focus();
      return;
    }
    s.queryInput.removeAttribute("aria-invalid");
    totalPages = 0;
    executeSearch(0);
  }

  async function executeSearch(requestedPage) {
    if (!s || searching || requestedPage < 0 || (totalPages > 0 && requestedPage >= totalPages)) {
      return;
    }
    if (!canRead) {
      setSearchState("forbidden");
      return;
    }
    searching = true;
    s.submitButton.disabled = true;
    setSearchState("loading");
    try {
      const params = new URLSearchParams({
        page: String(requestedPage),
        size: "10",
        sortBy: s.sortSelect.value || "name",
        direction: s.directionSelect.value || "asc"
      });
      const trimmedQuery = s.queryInput.value.trim();
      if (trimmedQuery.length >= 2) {
        params.set("query", trimmedQuery);
      }
      if (s.speciesFilter.value) {
        params.set("species", s.speciesFilter.value);
      }
      if (s.statusFilter.value) {
        params.set("status", s.statusFilter.value);
      }

      const result = validPatientPage(await client.requestJson(`/api/v1/patients?${params}`));
      page = result.page;
      totalPages = result.totalPages;
      renderPatients(result.content);
      updatePagination(result);
      setSearchState(result.content.length > 0 ? "content" : "empty");
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 403) {
        setSearchState("forbidden");
      } else if (error instanceof ApiRequestError && error.status === 400) {
        s.validation.textContent = error.message || "Los criterios de búsqueda no son válidos.";
        setSearchState("validation");
      } else {
        s.errorMessage.textContent = error instanceof ApiRequestError
          ? error.message
          : "No fue posible consultar el padrón de pacientes.";
        setSearchState("error");
      }
    } finally {
      searching = false;
      s.submitButton.disabled = false;
    }
  }

  function clearPatientDiagnoses() {
    if (!p) return;
    if (p.diagnosesList) {
      p.diagnosesList.replaceChildren();
      p.diagnosesList.hidden = true;
    }
    if (p.diagnosesLoading) p.diagnosesLoading.hidden = true;
    if (p.diagnosesError) p.diagnosesError.hidden = true;
    if (p.consultationsEmpty) p.consultationsEmpty.hidden = true;
  }

  async function openPatientProfile(patientId, options = {}) {
    if (!p || !patientId) {
      return;
    }
    const thisSession = currentSessionId;
    const thisRequestId = ++activeProfileRequestId;
    activeProfileId = String(patientId);
    currentProfile = null;

    clearPatientDiagnoses();

    if (s) {
      s.home.hidden = true;
      s.view.hidden = true;
    }
    p.view.hidden = false;
    if (p.editButton) {
      p.editButton.hidden = true;
    }
    if (p.notice && !options.keepNotice) {
      p.notice.hidden = true;
    }

    if (!canRead) {
      setProfileState("forbidden");
      p.forbidden.focus({ preventScroll: true });
      return;
    }

    loadingProfile = true;
    setProfileState("loading");
    p.title.focus({ preventScroll: true });

    try {
      const profile = validPatientProfile(
        await client.requestJson(`/api/v1/patients/${encodeURIComponent(activeProfileId)}`)
      );
      if (thisSession !== currentSessionId || thisRequestId !== activeProfileRequestId) {
        return;
      }

      currentProfile = profile;
      renderPatientProfile(profile);
      if (p.editButton) {
        p.editButton.hidden = !canUpdate;
      }
      if (p.newAppointmentButton) {
        const allowNewAppointment = canCreateAppointment && profile.status === "ACTIVO";
        p.newAppointmentButton.hidden = !allowNewAppointment;
        p.newAppointmentButton.disabled = !allowNewAppointment;
        p.newAppointmentButton.setAttribute("aria-disabled", String(!allowNewAppointment));
        p.newAppointmentButton.classList.toggle("btn-primary", allowNewAppointment);
        p.newAppointmentButton.classList.toggle("btn-outline-secondary", !allowNewAppointment);
        p.newAppointmentButton.textContent = allowNewAppointment ? "Nueva cita" : "Nueva cita (HU-016)";
      }
      setProfileState("content");
      if (canReadConsultations) {
        loadPatientDiagnoses(activeProfileId, thisRequestId, thisSession);
      } else {
        renderPatientDiagnoses(null);
      }
    } catch (error) {
      if (thisSession !== currentSessionId || thisRequestId !== activeProfileRequestId) {
        return;
      }
      if (error instanceof ApiRequestError && error.status === 403) {
        setProfileState("forbidden");
        p.forbidden.focus({ preventScroll: true });
      } else if (error instanceof ApiRequestError && error.status === 404) {
        setProfileState("not-found");
        p.notFound.focus({ preventScroll: true });
      } else {
        p.errorMessage.textContent = error instanceof ApiRequestError
          ? error.message
          : "No fue posible consultar el expediente clínico del paciente.";
        p.errorCorrelation.textContent = error instanceof ApiRequestError && error.correlationId
          ? `Código de seguimiento: ${error.correlationId}`
          : "";
        p.errorCorrelation.hidden = !(error instanceof ApiRequestError && error.correlationId);
        setProfileState("error");
        p.error.focus({ preventScroll: true });
      }
    } finally {
      if (thisSession === currentSessionId && thisRequestId === activeProfileRequestId) {
        loadingProfile = false;
      }
    }
  }

  async function loadPatientDiagnoses(patientId, requestId, sessionId) {
    if (!p) return;
    if (p.diagnosesLoading) p.diagnosesLoading.hidden = false;
    if (p.diagnosesList) p.diagnosesList.hidden = true;
    if (p.diagnosesError) p.diagnosesError.hidden = true;
    if (p.consultationsEmpty) p.consultationsEmpty.hidden = true;

    try {
      const diagnoses = await client.requestJson(`/api/v1/patients/${encodeURIComponent(patientId)}/diagnoses`);
      if (sessionId !== currentSessionId || requestId !== activeProfileRequestId || activeProfileId !== String(patientId)) {
        return;
      }

      if (!Array.isArray(diagnoses)) {
        throw new ApiRequestError({
          kind: "INVALID_RESPONSE",
          code: "INVALID_RESPONSE",
          message: "La respuesta de diagnósticos clínicos no tiene un formato de lista válido."
        });
      }

      renderPatientDiagnoses(diagnoses);
    } catch (error) {
      if (sessionId !== currentSessionId || requestId !== activeProfileRequestId || activeProfileId !== String(patientId)) {
        return;
      }
      showPatientDiagnosesError(error, patientId, requestId, sessionId);
    } finally {
      if (sessionId === currentSessionId && requestId === activeProfileRequestId && activeProfileId === String(patientId)) {
        if (p.diagnosesLoading) p.diagnosesLoading.hidden = true;
      }
    }
  }

  function showPatientDiagnosesError(error, patientId, requestId, sessionId) {
    if (!p) return;
    if (p.diagnosesList) {
      p.diagnosesList.hidden = true;
      p.diagnosesList.replaceChildren();
    }
    p.consultationsEmpty.hidden = true;

    if (p.diagnosesError) {
      p.diagnosesError.hidden = false;
      if (p.diagnosesErrorMessage) {
        p.diagnosesErrorMessage.textContent =
          error instanceof ApiRequestError
            ? error.message
            : (error?.message || "No fue posible consultar los diagnósticos clínicos del paciente.");
      }
      if (p.diagnosesErrorCorrelation) {
        const correlationId = error instanceof ApiRequestError ? error.correlationId : error?.correlationId;
        p.diagnosesErrorCorrelation.textContent = correlationId
          ? `Código de seguimiento: ${correlationId}`
          : "";
        p.diagnosesErrorCorrelation.hidden = !correlationId;
      }
      if (p.diagnosesRetry) {
        p.diagnosesRetry.onclick = () => {
          if (activeProfileId === String(patientId) && currentSessionId === sessionId) {
            loadPatientDiagnoses(patientId, activeProfileRequestId, currentSessionId);
          }
        };
      }
      p.diagnosesError.focus?.({ preventScroll: true });
    }
  }

  function renderPatientProfile(profile) {
    if (!p) return;
    const speciesKey = String(profile.species ?? "").toUpperCase();
    const speciesLabel = SPECIES_LABELS[speciesKey] ?? profile.species ?? "No especificada";
    const breedText = profile.breed?.trim() || "Sin raza registrada";
    const sexLabel = SEX_LABELS[profile.sex] ?? profile.sex ?? "No especificado";
    const birthDateText = profile.birthDate?.trim() || "No registrada";
    const colorText = profile.color?.trim() || "No registrado";
    const microchipText = profile.microchip?.trim() || "No registrado";
    const sterilizedText = profile.sterilized === true
      ? "Sí"
      : profile.sterilized === false
        ? "No"
        : "No especificado";
    const statusInfo = STATUS_META[profile.status] ?? {
      label: profile.status || "Desconocido",
      badgeClass: "badge text-bg-light border"
    };

    p.title.textContent = profile.name;
    p.subtitle.textContent = `${speciesLabel} · ${breedText} · ${sexLabel}`;
    p.statusBadge.className = statusInfo.badgeClass;
    p.statusBadge.textContent = statusInfo.label;

    p.name.textContent = profile.name;
    p.species.textContent = speciesLabel;
    p.breed.textContent = breedText;
    p.sex.textContent = sexLabel;
    p.birthDate.textContent = birthDateText;
    p.color.textContent = colorText;
    p.microchip.textContent = microchipText;
    p.sterilized.textContent = sterilizedText;

    const ownerName = profile.owner?.fullName?.trim() || "Sin propietario";
    const ownerDoc = [profile.owner?.documentType, profile.owner?.documentNumber].filter(Boolean).join(" ") || "Sin documento";
    const ownerPhone = profile.owner?.phone?.trim() || "No registrado";
    const ownerEmail = profile.owner?.email?.trim() || "No registrado";

    p.ownerName.textContent = ownerName;
    p.ownerDocument.textContent = ownerDoc;
    p.ownerPhone.textContent = ownerPhone;
    p.ownerEmail.textContent = ownerEmail;

    const allergies = profile.medicalRecord?.allergies?.trim() || "";
    const medicalAlerts = profile.medicalRecord?.medicalAlerts?.trim() || "";
    const hasCriticalAlerts = allergies.length > 0 || medicalAlerts.length > 0;

    p.alertsBanner.hidden = !hasCriticalAlerts;
    p.noAlerts.hidden = hasCriticalAlerts;
    p.allergiesRow.hidden = allergies.length === 0;
    p.allergiesValue.textContent = allergies;
    p.alertsRow.hidden = medicalAlerts.length === 0;
    p.alertsValue.textContent = medicalAlerts;

    const recordId = profile.medicalRecord?.id ?? null;
    const recordDate = formatIsoDate(profile.medicalRecord?.createdAt);
    p.recordMeta.textContent = recordId
      ? `Folio: ${recordId}${recordDate ? ` · Apertura: ${recordDate}` : ""}`
      : "Historia clínica base vinculada";
    p.recordHistory.textContent = profile.medicalRecord?.history?.trim() || "Sin antecedentes clínicos registrados.";
  }

  function renderPatientDiagnoses(diagnoses) {
    if (!p) return;
    if (p.diagnosesError) p.diagnosesError.hidden = true;
    if (p.diagnosesLoading) p.diagnosesLoading.hidden = true;
    if (diagnoses && diagnoses.length > 0) {
      p.consultationsEmpty.hidden = true;
      if (p.diagnosesList) {
        p.diagnosesList.hidden = false;
        p.diagnosesList.replaceChildren();
        for (const diag of diagnoses) {
          const item = document.createElement("div");
          item.className = "list-group-item px-0 py-2 border-bottom";
          const header = document.createElement("div");
          header.className = "d-flex justify-content-between align-items-center mb-1";
          const typeBadge = document.createElement("span");
          typeBadge.className = diag.diagnosisType === "DEFINITIVO"
            ? "badge text-bg-primary"
            : "badge text-bg-warning text-dark";
          typeBadge.textContent = diag.diagnosisType;
          const dateSpan = document.createElement("span");
          dateSpan.className = "small text-secondary pc-data-mono";
          dateSpan.textContent = formatIsoDate(diag.consultationStartedAt || diag.createdAt);
          header.append(typeBadge, dateSpan);

          const desc = document.createElement("div");
          desc.className = "fw-medium";
          desc.textContent = diag.description;
          if (diag.code) {
            const codeBadge = document.createElement("span");
            codeBadge.className = "badge text-bg-light border pc-data-mono ms-2";
            codeBadge.textContent = diag.code;
            desc.append(codeBadge);
          }
          item.append(header, desc);
          if (diag.notes) {
            const notes = document.createElement("div");
            notes.className = "small text-secondary mt-1";
            notes.textContent = diag.notes;
            item.append(notes);
          }
          p.diagnosesList.append(item);
        }
      }
    } else {
      if (p.diagnosesList) {
        p.diagnosesList.hidden = true;
        p.diagnosesList.replaceChildren();
      }
      p.consultationsEmpty.hidden = false;
      if (diagnoses === null) {
        p.consultationsEmpty.textContent = "Permiso insuficiente para consultar diagnósticos clínicos (requiere CONSULTATION_READ).";
      } else {
        p.consultationsEmpty.textContent = "Sin consultas ni diagnósticos registrados.";
      }
    }
  }

  function renderPatients(patients) {
    s.rows.replaceChildren();
    s.cards.replaceChildren();

    for (const patient of patients) {
      const patientId = patient.id;
      const breedText = patient.breed?.trim() || "Sin raza registrada";
      const speciesKey = String(patient.species ?? "").toUpperCase();
      const speciesLabel = SPECIES_LABELS[speciesKey] ?? patient.species;
      const sexLabel = SEX_LABELS[patient.sex] ?? patient.sex;
      const microchipText = patient.microchip?.trim() || "No registrado";
      const ownerName = patient.ownerFullName?.trim() || "Sin propietario";
      const ownerDoc = [patient.ownerDocumentType, patient.ownerDocumentNumber].filter(Boolean).join(" ") || "Sin documento";
      const statusInfo = STATUS_META[patient.status] ?? {
        label: patient.status || "Desconocido",
        badgeClass: "badge text-bg-light border"
      };

      const row = document.createElement("tr");
      row.dataset.patientId = patientId;

      const nameCell = document.createElement("td");
      const nameStrong = document.createElement("div");
      nameStrong.className = "fw-semibold";
      nameStrong.textContent = patient.name;
      const breedMeta = document.createElement("div");
      breedMeta.className = "small text-secondary";
      breedMeta.textContent = breedText;
      nameCell.append(nameStrong, breedMeta);

      const speciesCell = document.createElement("td");
      const speciesPrimary = document.createElement("div");
      speciesPrimary.textContent = speciesLabel;
      const sexSecondary = document.createElement("div");
      sexSecondary.className = "small text-secondary";
      sexSecondary.textContent = sexLabel;
      speciesCell.append(speciesPrimary, sexSecondary);

      const ownerCell = document.createElement("td");
      const ownerPrimary = document.createElement("div");
      ownerPrimary.className = "fw-medium";
      ownerPrimary.textContent = ownerName;
      const ownerSecondary = document.createElement("div");
      ownerSecondary.className = "small text-secondary pc-data-mono";
      ownerSecondary.textContent = ownerDoc;
      ownerCell.append(ownerPrimary, ownerSecondary);

      const microchipCell = document.createElement("td");
      const microchipSpan = document.createElement("span");
      microchipSpan.className = "pc-data-mono small";
      microchipSpan.textContent = microchipText;
      microchipCell.append(microchipSpan);

      const statusCell = document.createElement("td");
      statusCell.append(createStatusBadge(document, statusInfo));

      const actionCell = document.createElement("td");
      actionCell.className = "text-end";
      actionCell.append(createProfileSlot(document, patientId, patient.name, canRead, openPatientProfile));

      row.append(nameCell, speciesCell, ownerCell, microchipCell, statusCell, actionCell);
      s.rows.append(row);

      const card = document.createElement("article");
      card.className = "card border";
      card.dataset.patientId = patientId;
      const cardBody = document.createElement("div");
      cardBody.className = "card-body d-flex flex-column gap-2";

      const headerRow = document.createElement("div");
      headerRow.className = "d-flex justify-content-between align-items-start gap-2";
      const titleGroup = document.createElement("div");
      const cardTitle = document.createElement("h3");
      cardTitle.className = "h6 mb-0 fw-semibold";
      cardTitle.textContent = patient.name;
      const cardSubtitle = document.createElement("p");
      cardSubtitle.className = "small text-secondary mb-0";
      cardSubtitle.textContent = `${speciesLabel} · ${breedText} · ${sexLabel}`;
      titleGroup.append(cardTitle, cardSubtitle);
      headerRow.append(titleGroup, createStatusBadge(document, statusInfo));

      const detailsGrid = document.createElement("div");
      detailsGrid.className = "small d-flex flex-column gap-1";
      const ownerLine = document.createElement("div");
      ownerLine.textContent = `Propietario: ${ownerName} (${ownerDoc})`;
      const microchipLine = document.createElement("div");
      microchipLine.className = "pc-data-mono text-secondary";
      microchipLine.textContent = `Microchip: ${microchipText}`;
      detailsGrid.append(ownerLine, microchipLine);

      const cardFooter = document.createElement("div");
      cardFooter.className = "d-flex justify-content-end pt-1 border-top";
      cardFooter.append(createProfileSlot(document, patientId, patient.name, canRead, openPatientProfile));

      cardBody.append(headerRow, detailsGrid, cardFooter);
      card.append(cardBody);
      s.cards.append(card);
    }
  }

  function updatePagination(result) {
    s.previousButton.disabled = result.page <= 0;
    s.nextButton.disabled = result.page + 1 >= result.totalPages;
    s.pageStatus.textContent = result.totalPages > 0
      ? `Página ${result.page + 1} de ${result.totalPages} · ${result.totalElements} pacientes`
      : "Sin resultados";
  }

  function setSearchState(activeState) {
    if (!s) return;
    s.initial.hidden = activeState !== "initial";
    s.validation.hidden = activeState !== "validation";
    s.forbidden.hidden = activeState !== "forbidden";
    s.loading.hidden = activeState !== "loading";
    s.empty.hidden = activeState !== "empty";
    s.error.hidden = activeState !== "error";
    s.content.hidden = activeState !== "content";
    if (s.pagination) {
      s.pagination.hidden = activeState !== "content";
    }
  }

  function setProfileState(activeState) {
    if (!p) return;
    p.loading.hidden = activeState !== "loading";
    p.forbidden.hidden = activeState !== "forbidden";
    p.notFound.hidden = activeState !== "not-found";
    p.error.hidden = activeState !== "error";
    p.content.hidden = activeState !== "content";
  }

  function openCreateDialog() {
    if (!canCreate) return;
    editingPatientId = null;
    if (e.title) {
      e.title.textContent = "Registrar paciente";
    }
    e.save.textContent = "Registrar paciente";
    e.form.reset();
    e.form.classList.remove("was-validated");
    e.error.hidden = true;
    e.validation.hidden = true;
    for (const input of e.form.querySelectorAll("input,select")) {
      input.removeAttribute("aria-invalid");
    }
    e.ownerOptions.replaceChildren(new Option("Busca un propietario", ""));
    e.dialog.showModal();
    e.ownerQuery.focus();
  }

  function openEditPatientDialog() {
    if (!canUpdate || !currentProfile) return;
    editingPatientId = currentProfile.id;
    if (e.title) {
      e.title.textContent = "Editar paciente";
    }
    e.save.textContent = "Guardar cambios";
    e.form.reset();
    e.form.classList.remove("was-validated");
    e.error.hidden = true;
    e.validation.hidden = true;
    for (const input of e.form.querySelectorAll("input,select")) {
      input.removeAttribute("aria-invalid");
    }

    const ownerId = currentProfile.owner?.id ?? "";
    const ownerLabel = ownerId
      ? `${currentProfile.owner.fullName}${currentProfile.owner.phone ? ` · ${currentProfile.owner.phone}` : ""}`
      : "Propietario actual";
    e.ownerOptions.replaceChildren(new Option(ownerLabel, ownerId, true, true));
    e.ownerOptions.value = ownerId;

    e.name.value = currentProfile.name ?? "";
    e.species.value = currentProfile.species ?? "";
    e.breed.value = currentProfile.breed ?? "";
    e.sex.value = currentProfile.sex ?? "NO_ESPECIFICADO";
    e.birthDate.value = currentProfile.birthDate ?? "";
    e.color.value = currentProfile.color ?? "";
    e.microchip.value = currentProfile.microchip ?? "";
    e.sterilized.checked = Boolean(currentProfile.sterilized);

    e.dialog.showModal();
    e.name.focus();
  }

  async function findOwners() {
    const query = e.ownerQuery.value.trim();
    if (query.length < 2) {
      e.error.textContent = "Ingresa al menos dos caracteres para buscar al propietario.";
      e.error.hidden = false;
      return;
    }
    e.ownerSearch.disabled = true;
    try {
      const result = await client.requestJson(`/api/v1/owners?${new URLSearchParams({ query, page: "0", size: "20", sortBy: "name", direction: "asc" })}`);
      const options = [
        new Option("Selecciona un propietario", ""),
        ...result.content.map((owner) => new Option(`${owner.firstName} ${owner.lastName} · ${owner.phone}`, owner.id))
      ];
      if (editingPatientId && currentProfile?.owner?.id && !result.content.some((owner) => owner.id === currentProfile.owner.id)) {
        const currentOwnerLabel = `${currentProfile.owner.fullName}${currentProfile.owner.phone ? ` · ${currentProfile.owner.phone}` : ""} (actual)`;
        options.splice(1, 0, new Option(currentOwnerLabel, currentProfile.owner.id));
      }
      e.ownerOptions.replaceChildren(...options);
      e.error.hidden = true;
    } catch (error) {
      e.error.textContent = error instanceof ApiRequestError ? error.message : "No fue posible buscar propietarios.";
      e.error.hidden = false;
    } finally {
      e.ownerSearch.disabled = false;
    }
  }

  async function save(event) {
    event.preventDefault();
    const isEditing = Boolean(editingPatientId);
    if (loading || (isEditing ? !canUpdate : !canCreate)) return;
    e.form.classList.add("was-validated");
    const invalid = [...e.form.querySelectorAll("input,select")].filter((input) => !input.checkValidity());
    for (const input of e.form.querySelectorAll("input,select")) {
      if (invalid.includes(input)) input.setAttribute("aria-invalid", "true");
      else input.removeAttribute("aria-invalid");
    }
    if (invalid.length) {
      e.validation.textContent = isEditing
        ? "Corrige los campos indicados antes de guardar los cambios del paciente."
        : "Corrige los campos indicados antes de registrar el paciente.";
      e.validation.hidden = false;
      invalid[0].focus();
      return;
    }
    loading = true;
    e.save.disabled = true;
    e.error.hidden = true;
    try {
      const targetUrl = isEditing
        ? `/api/v1/patients/${encodeURIComponent(editingPatientId)}`
        : "/api/v1/patients";
      const savedPatient = await client.requestJson(targetUrl, {
        method: isEditing ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ownerId: e.ownerOptions.value,
          name: e.name.value,
          species: e.species.value,
          breed: e.breed.value,
          sex: e.sex.value,
          birthDate: e.birthDate.value || null,
          color: e.color.value,
          microchip: e.microchip.value,
          sterilized: e.sterilized.checked
        })
      });
      const updatedId = editingPatientId;
      editingPatientId = null;
      e.dialog.close();
      if (isEditing && p && updatedId) {
        await openPatientProfile(updatedId, { keepNotice: true });
        if (p.notice) {
          p.notice.textContent = "Datos del paciente actualizados correctamente.";
          p.notice.hidden = false;
          p.notice.focus({ preventScroll: true });
        }
        if (s && canRead && s.rows.childElementCount > 0) {
          await executeSearch(page);
        }
      } else {
        e.notice.hidden = false;
        e.notice.focus();
        if (s && canRead && !s.view.hidden && savedPatient?.name) {
          s.queryInput.value = savedPatient.name;
          totalPages = 0;
          await executeSearch(0);
        }
      }
    } catch (error) {
      e.error.textContent = error instanceof ApiRequestError
        ? error.message
        : (isEditing ? "No fue posible actualizar el paciente." : "No fue posible registrar el paciente.");
      e.error.hidden = false;
    } finally {
      loading = false;
      e.save.disabled = false;
    }
  }
}

function createStatusBadge(document, statusInfo) {
  const badge = document.createElement("span");
  badge.className = statusInfo.badgeClass;
  badge.textContent = statusInfo.label;
  return badge;
}

function createProfileSlot(document, patientId, patientName, canRead, onOpenProfile) {
  const slot = document.createElement("div");
  slot.dataset.patientProfileSlot = "true";
  slot.dataset.patientId = patientId;
  if (!canRead) {
    slot.hidden = true;
    return slot;
  }

  const button = document.createElement("button");
  button.type = "button";
  button.className = "btn btn-outline-primary btn-sm";
  button.dataset.action = "view-patient-profile";
  button.dataset.patientId = patientId;
  button.setAttribute("aria-label", `Ver perfil de ${patientName}`);
  button.textContent = "Ver perfil";
  button.addEventListener("click", () => onOpenProfile(patientId));

  slot.append(button);
  return slot;
}

function formatIsoDate(isoString) {
  if (!isoString || typeof isoString !== "string") {
    return "";
  }
  return isoString.slice(0, 10);
}

function elements(document) {
  const s = {
    open: "#new-patient-button",
    dialog: "#patient-editor-dialog",
    form: "#patient-editor-form",
    ownerQuery: "#patient-owner-query",
    ownerSearch: "#patient-owner-search",
    ownerOptions: "#patient-owner-id",
    name: "#patient-name",
    species: "#patient-species",
    breed: "#patient-breed",
    sex: "#patient-sex",
    birthDate: "#patient-birth-date",
    color: "#patient-color",
    microchip: "#patient-microchip",
    sterilized: "#patient-sterilized",
    save: "#save-patient-button",
    cancel: "#cancel-patient-button",
    error: "#patient-form-error",
    validation: "#patient-form-validation",
    notice: "#patient-notice"
  };
  const e = Object.fromEntries(Object.entries(s).map(([k, v]) => [k, document.querySelector(v)]));
  for (const [k, v] of Object.entries(e)) {
    if (!v) throw new Error(`Falta ${k}`);
  }
  e.title = document.querySelector("#patient-editor-title");
  return e;
}

function searchElements(document) {
  const view = document.querySelector("#patients-view");
  if (!view) {
    return null;
  }
  const selectors = {
    navButton: "#patients-nav-button",
    home: "#dashboard-home",
    view: "#patients-view",
    backButton: "#patients-back-button",
    title: "#patients-title",
    searchForm: "#patient-search-form",
    queryInput: "#patient-search-query",
    speciesFilter: "#patient-species-filter",
    statusFilter: "#patient-status-filter",
    sortSelect: "#patient-sort",
    directionSelect: "#patient-direction",
    submitButton: "#patient-search-submit",
    clearButton: "#clear-patient-filters",
    initial: "#patients-initial",
    validation: "#patients-validation",
    forbidden: "#patients-forbidden",
    loading: "#patients-loading",
    empty: "#patients-empty",
    error: "#patients-error",
    errorMessage: "#patients-error-message",
    retryButton: "#retry-patients",
    content: "#patients-content",
    rows: "#patients-table-body",
    cards: "#patients-cards",
    pagination: "#patients-pagination",
    pageStatus: "#patients-page-status",
    previousButton: "#patients-previous-page",
    nextButton: "#patients-next-page"
  };
  const resolved = Object.fromEntries(
    Object.entries(selectors).map(([key, selector]) => [key, document.querySelector(selector)])
  );
  for (const [key, element] of Object.entries(resolved)) {
    if (!element) {
      throw new Error(`Falta elemento de búsqueda de pacientes: ${key}`);
    }
  }
  resolved.newPatientViewButton = document.querySelector("#patients-new-patient-button");
  return resolved;
}

function profileElements(document) {
  const view = document.querySelector("#patient-profile-view");
  if (!view) {
    return null;
  }
  const selectors = {
    view: "#patient-profile-view",
    title: "#patient-profile-title",
    subtitle: "#patient-profile-subtitle",
    statusBadge: "#patient-profile-status-badge",
    backButton: "#patient-profile-back-button",
    newConsultationButton: "#patient-profile-new-consultation-button",
    newAppointmentButton: "#patient-profile-new-appointment-button",
    loading: "#patient-profile-loading",
    forbidden: "#patient-profile-forbidden",
    notFound: "#patient-profile-not-found",
    error: "#patient-profile-error",
    errorMessage: "#patient-profile-error-message",
    errorCorrelation: "#patient-profile-error-correlation",
    retryButton: "#retry-patient-profile",
    content: "#patient-profile-content",
    alertsBanner: "#patient-profile-medical-alerts-banner",
    allergiesRow: "#patient-profile-allergies-row",
    allergiesValue: "#patient-profile-allergies-value",
    alertsRow: "#patient-profile-alerts-row",
    alertsValue: "#patient-profile-alerts-value",
    noAlerts: "#patient-profile-no-alerts",
    name: "#patient-profile-name",
    species: "#patient-profile-species",
    breed: "#patient-profile-breed",
    sex: "#patient-profile-sex",
    birthDate: "#patient-profile-birth-date",
    color: "#patient-profile-color",
    microchip: "#patient-profile-microchip",
    sterilized: "#patient-profile-sterilized",
    ownerName: "#patient-profile-owner-name",
    ownerDocument: "#patient-profile-owner-document",
    ownerPhone: "#patient-profile-owner-phone",
    ownerEmail: "#patient-profile-owner-email",
    recordMeta: "#patient-profile-record-meta",
    recordHistory: "#patient-profile-record-history",
    consultationsEmpty: "#patient-profile-consultations-empty",
    appointmentsEmpty: "#patient-profile-appointments-empty",
    vaccinesEmpty: "#patient-profile-vaccines-empty",
    prescriptionsEmpty: "#patient-profile-prescriptions-empty"
  };
  const resolved = Object.fromEntries(
    Object.entries(selectors).map(([key, selector]) => [key, document.querySelector(selector)])
  );
  for (const [key, element] of Object.entries(resolved)) {
    if (!element) {
      throw new Error(`Falta elemento de perfil de paciente: ${key}`);
    }
  }
  resolved.editButton = document.querySelector("#patient-profile-edit-button");
  resolved.notice = document.querySelector("#patient-profile-notice");
  resolved.diagnosesList = document.querySelector("#patient-profile-diagnoses-list");
  resolved.diagnosesLoading = document.querySelector("#patient-profile-diagnoses-loading");
  resolved.diagnosesError = document.querySelector("#patient-profile-diagnoses-error");
  resolved.diagnosesErrorMessage = document.querySelector("#patient-profile-diagnoses-error-message");
  resolved.diagnosesErrorCorrelation = document.querySelector("#patient-profile-diagnoses-error-correlation");
  resolved.diagnosesRetry = document.querySelector("#patient-profile-diagnoses-retry");
  return resolved;
}

function validPatientPage(value) {
  if (
    !value
    || !Array.isArray(value.content)
    || !Number.isInteger(value.page)
    || !Number.isInteger(value.totalPages)
  ) {
    throw new ApiRequestError({
      kind: "INVALID_RESPONSE",
      code: "INVALID_RESPONSE",
      message: "Respuesta del padrón de pacientes no válida."
    });
  }
  return value;
}

function validPatientProfile(value) {
  if (
    !value
    || typeof value.id !== "string"
    || typeof value.name !== "string"
    || !value.owner
    || typeof value.owner.fullName !== "string"
  ) {
    throw new ApiRequestError({
      kind: "INVALID_RESPONSE",
      code: "INVALID_RESPONSE",
      message: "Respuesta del perfil del paciente no válida."
    });
  }
  return value;
}

function bindPatientValidationDescriptions(document) {
  const form = document.querySelector("#patient-editor-form");
  if (!form) return;
  const fields = [
    ["#patient-owner-id", "patient-owner-id-feedback"],
    ["#patient-name", "patient-name-feedback"],
    ["#patient-species", "patient-species-feedback"],
    ["#patient-sex", "patient-sex-feedback"]
  ];
  const feedback = [...form.querySelectorAll(".invalid-feedback")];
  fields.forEach(([selector, id], index) => {
    const field = form.querySelector(selector);
    const message = feedback[index];
    if (field && message) {
      message.id = id;
      field.setAttribute("aria-describedby", id);
    }
  });
}

bindPatientValidationDescriptions(document);
