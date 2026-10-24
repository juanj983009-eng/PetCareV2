import { ApiRequestError } from "./api-client.js";

const APPOINTMENT_READ = "APPOINTMENT_READ";
const APPOINTMENT_CREATE = "APPOINTMENT_CREATE";
const APPOINTMENT_STATUS_UPDATE = "APPOINTMENT_STATUS_UPDATE";
const APPOINTMENT_RESCHEDULE = "APPOINTMENT_RESCHEDULE";
const APPOINTMENT_CANCEL = "APPOINTMENT_CANCEL";
const CONSULTATION_CREATE = "CONSULTATION_CREATE";
const PATIENT_READ = "PATIENT_READ";

const APPOINTMENT_TYPE_LABELS = Object.freeze({
  CONSULTA_GENERAL: "Consulta general",
  VACUNACION: "Vacunación",
  CONTROL: "Control",
  URGENCIA: "Urgencia",
  CIRUGIA: "Cirugía",
  DESPARASITACION: "Desparasitación"
});

const STATUS_META = Object.freeze({
  PROGRAMADA: {
    icon: "○",
    label: "Programada",
    badgeClass: "badge text-bg-secondary-subtle text-secondary-emphasis border border-secondary-subtle"
  },
  CONFIRMADA: {
    icon: "●",
    label: "Confirmada",
    badgeClass: "badge text-bg-primary-subtle text-primary-emphasis border border-primary-subtle"
  },
  EN_ESPERA: {
    icon: "◔",
    label: "En espera",
    badgeClass: "badge text-bg-warning-subtle text-warning-emphasis border border-warning-subtle"
  },
  EN_ATENCION: {
    icon: "▶",
    label: "En atención",
    badgeClass: "badge text-bg-info-subtle text-info-emphasis border border-info-subtle"
  },
  FINALIZADA: {
    icon: "✓",
    label: "Finalizada",
    badgeClass: "badge text-bg-success-subtle text-success-emphasis border border-success-subtle"
  },
  CANCELADA: {
    icon: "✕",
    label: "Cancelada",
    badgeClass: "badge text-bg-danger-subtle text-danger-emphasis border border-danger-subtle"
  },
  NO_ASISTIO: {
    icon: "▲",
    label: "No asistió",
    badgeClass: "badge text-bg-dark-subtle text-dark-emphasis border border-dark-subtle"
  }
});

export function createAppointmentsController({
  document,
  client,
  onViewPatientProfile = null,
  onAppointmentCreated = null
}) {
  const s = findElements(document);
  const d = findDialogElements(document);
  const r = findRescheduleDialogElements(document);
  const c = findCancelDialogElements(document);
  let canRead = false;
  let canCreate = false;
  let canUpdateStatus = false;
  let canReschedule = false;
  let canCancel = false;
  let canStartConsultation = false;
  let canReadPatient = false;
  let currentUserId = null;
  let searching = false;
  let savingAppointment = false;
  let reschedulingAppointment = false;
  let cancellingAppointment = false;
  let reschedulingAppointmentId = null;
  let cancellingAppointmentId = null;
  let searchingPatients = false;
  let optionsLoaded = false;
  let page = 0;
  let totalPages = 0;
  const updatingAppointmentIds = new Set();
  const renderedItemsById = new Map();
  const branchTimeZones = new Map();
  let branchesCatalog = [];
  let veterinariansCatalog = [];

  if (s) {
    s.navButton.addEventListener("click", openAppointmentsView);
    s.backButton.addEventListener("click", closeAppointmentsView);
    s.newAppointmentViewButton?.addEventListener("click", () => openCreateAppointmentDialog());
    s.searchForm.addEventListener("submit", submitSearch);
    s.clearButton.addEventListener("click", clearFilters);
    s.retryButton.addEventListener("click", () => executeSearch(page));
    s.previousButton.addEventListener("click", () => executeSearch(page - 1));
    s.nextButton.addEventListener("click", () => executeSearch(page + 1));
    s.view.addEventListener("click", handleDelegatedClick);
  }

  if (d) {
    d.cancelButton.addEventListener("click", () => d.dialog.close());
    d.branchSelect.addEventListener("change", handleDialogBranchChange);
    d.patientSearchButton.addEventListener("click", searchPatientsForDialog);
    d.patientQuery.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        searchPatientsForDialog();
      }
    });
    d.form.addEventListener("submit", submitCreateAppointment);
  }

  if (r && r.cancelButton && r.branchSelect && r.form) {
    r.cancelButton.addEventListener("click", closeRescheduleDialog);
    r.branchSelect.addEventListener("change", handleRescheduleBranchChange);
    r.form.addEventListener("submit", (event) => {
      event.preventDefault();
      submitRescheduleAppointment();
    });
  }

  if (c) {
    c.cancelButton.addEventListener("click", closeCancelDialog);
    c.form.addEventListener("submit", (event) => {
      event.preventDefault();
      submitCancelAppointment();
    });
  }

  return Object.freeze({
    sessionReady(identity) {
      const authorities = new Set(identity?.authorities ?? []);
      canRead = authorities.has(APPOINTMENT_READ);
      canCreate = authorities.has(APPOINTMENT_CREATE);
      canUpdateStatus = authorities.has(APPOINTMENT_STATUS_UPDATE);
      canReschedule = authorities.has(APPOINTMENT_RESCHEDULE);
      canCancel = authorities.has(APPOINTMENT_CANCEL);
      canStartConsultation = authorities.has(CONSULTATION_CREATE);
      canReadPatient = authorities.has(PATIENT_READ);
      currentUserId = typeof identity?.userId === "string" && identity.userId.trim()
        ? identity.userId.trim()
        : null;
      updatingAppointmentIds.clear();
      renderedItemsById.clear();
      reschedulingAppointmentId = null;
      cancellingAppointmentId = null;
      optionsLoaded = false;
      branchTimeZones.clear();
      branchesCatalog = [];
      veterinariansCatalog = [];
      if (d?.dialog.open) {
        d.dialog.close();
      }
      if (r?.dialog.open) {
        r.dialog.close();
      }
      if (c?.dialog.open) {
        c.dialog.close();
      }
      if (s) {
        s.navButton.hidden = !canRead;
        syncCreateButtonState();
        if (s.notice) {
          s.notice.hidden = true;
        }
        if (s.statusError) {
          s.statusError.hidden = true;
          s.statusError.textContent = "";
        }
        closeAppointmentsView();
        resetStateToDefaultDate();
      }
    },
    sessionEnded() {
      canRead = false;
      canCreate = false;
      canUpdateStatus = false;
      canReschedule = false;
      canCancel = false;
      canStartConsultation = false;
      canReadPatient = false;
      currentUserId = null;
      updatingAppointmentIds.clear();
      renderedItemsById.clear();
      reschedulingAppointmentId = null;
      cancellingAppointmentId = null;
      optionsLoaded = false;
      branchTimeZones.clear();
      branchesCatalog = [];
      veterinariansCatalog = [];
      if (d?.dialog.open) {
        d.dialog.close();
      }
      if (r?.dialog.open) {
        r.dialog.close();
      }
      if (c?.dialog.open) {
        c.dialog.close();
      }
      if (s) {
        s.navButton.hidden = true;
        syncCreateButtonState();
        if (s.notice) {
          s.notice.hidden = true;
        }
        if (s.statusError) {
          s.statusError.hidden = true;
          s.statusError.textContent = "";
        }
        s.view.hidden = true;
        s.home.hidden = false;
        resetStateToDefaultDate();
      }
    },
    openAppointmentsView,
    openCreateAppointmentDialog
  });

  function syncCreateButtonState() {
    if (!s?.newAppointmentViewButton) return;
    s.newAppointmentViewButton.hidden = !canCreate;
    s.newAppointmentViewButton.disabled = !canCreate;
    s.newAppointmentViewButton.setAttribute("aria-disabled", String(!canCreate));
  }

  async function openAppointmentsView() {
    if (!s) return;
    s.home.hidden = true;
    s.view.hidden = false;
    if (s.notice) {
      s.notice.hidden = true;
    }
    if (!canRead) {
      setViewState("forbidden");
      s.forbidden.focus({ preventScroll: true });
      return;
    }
    if (!s.dateFilter.value) {
      s.dateFilter.value = todayLocalIsoDate();
    }
    s.title.focus({ preventScroll: true });
    if (!optionsLoaded) {
      await loadOptions();
    }
    await executeSearch(0);
  }

  function closeAppointmentsView() {
    if (!s) return;
    s.view.hidden = true;
    s.home.hidden = false;
  }

  function resetStateToDefaultDate() {
    if (!s) return;
    page = 0;
    totalPages = 0;
    s.searchForm.reset();
    s.dateFilter.value = todayLocalIsoDate();
    s.dateFilter.removeAttribute("aria-invalid");
    s.branchFilter.replaceChildren(createOption("", "Todas las sucursales"));
    s.veterinarianFilter.replaceChildren(createOption("", "Todos los veterinarios"));
    s.directionFilter.value = "asc";
    s.rows.replaceChildren();
    s.cards.replaceChildren();
    setViewState("initial");
  }

  async function clearFilters() {
    if (!s) return;
    page = 0;
    totalPages = 0;
    s.dateFilter.value = todayLocalIsoDate();
    s.dateFilter.removeAttribute("aria-invalid");
    s.branchFilter.value = "";
    s.veterinarianFilter.value = "";
    s.directionFilter.value = "asc";
    if (s.notice) {
      s.notice.hidden = true;
    }
    if (canRead) {
      await executeSearch(0);
    } else {
      setViewState("initial");
    }
  }

  async function loadOptions() {
    if (!s || (!canRead && !canCreate && !canReschedule)) return;
    try {
      const response = await client.requestJson("/api/v1/appointments/options");
      branchTimeZones.clear();
      branchesCatalog = Array.isArray(response?.branches)
        ? response.branches.filter((b) => b?.id && b?.name)
        : [];
      veterinariansCatalog = Array.isArray(response?.veterinarians)
        ? response.veterinarians.filter((v) => v?.id && v?.fullName)
        : [];

      const branchOptions = [createOption("", "Todas las sucursales")];
      for (const branch of branchesCatalog) {
        branchTimeZones.set(branch.id, branch.timeZone || "America/Lima");
        branchOptions.push(createOption(branch.id, branch.name));
      }
      s.branchFilter.replaceChildren(...branchOptions);

      const vetOptions = [createOption("", "Todos los veterinarios")];
      for (const vet of veterinariansCatalog) {
        vetOptions.push(createOption(vet.id, vet.fullName));
      }
      s.veterinarianFilter.replaceChildren(...vetOptions);
      optionsLoaded = true;
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 403 && !canCreate && !canReschedule) {
        setViewState("forbidden");
      }
    }
  }

  async function openCreateAppointmentDialog(preselectedPatient = null) {
    if (!d || !canCreate) return;
    if (!optionsLoaded) {
      await loadOptions();
    }
    clearDialogMessages();
    d.form.reset();

    // Populate branches in modal
    const branchOpts = [createOption("", "Selecciona una sucursal")];
    for (const branch of branchesCatalog) {
      branchOpts.push(createOption(branch.id, branch.name));
    }
    d.branchSelect.replaceChildren(...branchOpts);

    const defaultBranchId = (s?.branchFilter?.value && branchTimeZones.has(s.branchFilter.value))
      ? s.branchFilter.value
      : (branchesCatalog.length === 1 ? branchesCatalog[0].id : "");
    d.branchSelect.value = defaultBranchId;
    handleDialogBranchChange();

    if (s?.veterinarianFilter?.value) {
      const matchingVetOption = Array.from(d.veterinarianSelect.options)
        .some((opt) => opt.value === s.veterinarianFilter.value);
      if (matchingVetOption) {
        d.veterinarianSelect.value = s.veterinarianFilter.value;
      }
    }

    // Populate patient selector
    if (preselectedPatient?.id) {
      const patientLabel = preselectedPatient.ownerFullName
        ? `${preselectedPatient.name} — ${preselectedPatient.ownerFullName}`
        : (preselectedPatient.name || "Paciente seleccionado");
      d.patientSearchGroup.hidden = true;
      d.patientSelect.replaceChildren(createOption(preselectedPatient.id, patientLabel));
      d.patientSelect.value = preselectedPatient.id;
    } else {
      d.patientSearchGroup.hidden = false;
      d.patientQuery.value = "";
      d.patientSelect.replaceChildren(createOption("", "Busca y selecciona un paciente activo"));
      d.patientSelect.value = "";
    }

    d.dateInput.value = (s?.dateFilter?.value && isValidIsoDate(s.dateFilter.value))
      ? s.dateFilter.value
      : todayLocalIsoDate();
    d.startTimeInput.value = "09:00";
    d.endTimeInput.value = "09:30";
    d.typeSelect.value = "CONSULTA_GENERAL";
    d.reasonInput.value = "";
    d.notesInput.value = "";

    if (!d.dialog.open) {
      d.dialog.showModal();
    }
  }

  function handleDialogBranchChange() {
    if (!d) return;
    const selectedBranchId = d.branchSelect.value.trim();
    const tz = selectedBranchId ? branchTimeZones.get(selectedBranchId) : null;
    d.branchTimeZone.textContent = `Zona horaria: ${tz || "—"}`;

    const vetOpts = [createOption("", "Selecciona un veterinario")];
    const eligibleVets = veterinariansCatalog.filter((vet) => {
      if (!selectedBranchId) return true;
      if (!Array.isArray(vet.branchIds)) return true;
      return vet.branchIds.includes(selectedBranchId);
    });
    for (const vet of eligibleVets) {
      vetOpts.push(createOption(vet.id, vet.fullName));
    }
    d.veterinarianSelect.replaceChildren(...vetOpts);
    if (eligibleVets.length === 1) {
      d.veterinarianSelect.value = eligibleVets[0].id;
    }
  }

  async function searchPatientsForDialog() {
    if (!d || searchingPatients) return;
    clearDialogMessages();
    const query = d.patientQuery.value.trim();
    if (query.length < 2) {
      d.patientQuery.setAttribute("aria-invalid", "true");
      d.validationAlert.textContent = "Ingresa al menos 2 caracteres para buscar un paciente activo.";
      d.validationAlert.hidden = false;
      d.patientQuery.focus();
      return;
    }
    d.patientQuery.removeAttribute("aria-invalid");
    searchingPatients = true;
    d.patientSearchButton.disabled = true;

    try {
      const params = new URLSearchParams({
        query,
        page: "0",
        size: "20",
        sortBy: "name",
        direction: "asc"
      });
      const response = await client.requestJson(`/api/v1/patients?${params.toString()}`);
      const allItems = Array.isArray(response?.content) ? response.content : [];
      const activePatients = allItems.filter((item) => item?.id && item?.status === "ACTIVO");

      if (activePatients.length === 0) {
        d.patientSelect.replaceChildren(createOption("", `Sin pacientes activos para "${query}"`));
        d.patientSelect.value = "";
        return;
      }

      const opts = [createOption("", "Selecciona un paciente activo")];
      for (const item of activePatients) {
        const ownerPart = item.ownerFullName ? ` — ${item.ownerFullName}` : "";
        opts.push(createOption(item.id, `${item.name}${ownerPart}`));
      }
      d.patientSelect.replaceChildren(...opts);
      d.patientSelect.value = activePatients[0].id;
    } catch (error) {
      d.errorAlert.textContent = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible buscar pacientes en este momento.";
      d.errorAlert.hidden = false;
    } finally {
      searchingPatients = false;
      d.patientSearchButton.disabled = false;
    }
  }

  async function submitCreateAppointment(event) {
    event.preventDefault();
    if (!d || savingAppointment || !canCreate) return;
    clearDialogMessages();

    const patientId = d.patientSelect.value.trim();
    const branchId = d.branchSelect.value.trim();
    const veterinarianId = d.veterinarianSelect.value.trim();
    const dateValue = d.dateInput.value.trim();
    const startTimeValue = d.startTimeInput.value.trim();
    const endTimeValue = d.endTimeInput.value.trim();
    const appointmentType = d.typeSelect.value.trim();
    const reasonValue = d.reasonInput.value.trim();
    const notesValue = d.notesInput.value.trim();

    if (!patientId) {
      markFieldInvalid(d.patientSelect, "Selecciona un paciente activo para programar la cita.");
      return;
    }
    if (!branchId) {
      markFieldInvalid(d.branchSelect, "Selecciona una sucursal activa.");
      return;
    }
    if (!veterinarianId) {
      markFieldInvalid(d.veterinarianSelect, "Selecciona un veterinario activo asignado a la sucursal.");
      return;
    }
    if (!isValidIsoDate(dateValue)) {
      markFieldInvalid(d.dateInput, "Ingresa una fecha válida en formato YYYY-MM-DD.");
      return;
    }
    if (!/^\d{2}:\d{2}$/.test(startTimeValue)) {
      markFieldInvalid(d.startTimeInput, "Ingresa la hora de inicio en formato HH:mm.");
      return;
    }
    if (!/^\d{2}:\d{2}$/.test(endTimeValue) || endTimeValue <= startTimeValue) {
      markFieldInvalid(d.endTimeInput, "La hora de término debe ser posterior a la hora de inicio.");
      return;
    }
    if (!appointmentType) {
      markFieldInvalid(d.typeSelect, "Selecciona un tipo de cita válido.");
      return;
    }

    const payload = {
      patientId,
      branchId,
      veterinarianId,
      startsAtLocal: `${dateValue}T${startTimeValue}`,
      endsAtLocal: `${dateValue}T${endTimeValue}`,
      appointmentType
    };
    if (reasonValue) {
      payload.reason = reasonValue;
    }
    if (notesValue) {
      payload.notes = notesValue;
    }

    savingAppointment = true;
    d.saveButton.disabled = true;
    d.cancelButton.disabled = true;
    const originalButtonText = d.saveButton.textContent;
    d.saveButton.textContent = "Programando...";

    try {
      const created = await client.requestJson("/api/v1/appointments", {
        method: "POST",
        body: payload
      });
      d.dialog.close();

      const confirmationMessage = `Cita programada correctamente para ${created?.patientName || "el paciente"} (${formatAppointmentType(created?.appointmentType)}).`;
      if (s?.notice) {
        s.notice.textContent = confirmationMessage;
        s.notice.hidden = false;
      }
      if (typeof onAppointmentCreated === "function") {
        onAppointmentCreated(created, confirmationMessage);
      }

      if (s && !s.view.hidden && canRead) {
        s.dateFilter.value = dateValue;
        await executeSearch(0);
      }
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 409) {
        d.errorAlert.textContent = error.message
          || "El veterinario seleccionado ya tiene una cita activa que se solapa con ese horario.";
        d.errorAlert.hidden = false;
        d.errorAlert.focus({ preventScroll: true });
      } else if (error instanceof ApiRequestError && error.status === 400) {
        const fieldMessages = Array.isArray(error.fieldErrors) && error.fieldErrors.length > 0
          ? error.fieldErrors.map((fe) => fe.message).join(" ")
          : (error.message || "Revisa los campos del formulario de cita.");
        d.validationAlert.textContent = fieldMessages;
        d.validationAlert.hidden = false;
        d.validationAlert.focus({ preventScroll: true });
      } else {
        d.errorAlert.textContent = (error instanceof ApiRequestError && error.message)
          ? error.message
          : "No fue posible programar la cita.";
        d.errorAlert.hidden = false;
        d.errorAlert.focus({ preventScroll: true });
      }
    } finally {
      savingAppointment = false;
      d.saveButton.disabled = false;
      d.cancelButton.disabled = false;
      d.saveButton.textContent = originalButtonText;
    }
  }

  function markFieldInvalid(fieldElement, message) {
    if (!d) return;
    fieldElement.setAttribute("aria-invalid", "true");
    d.validationAlert.textContent = message;
    d.validationAlert.hidden = false;
    fieldElement.focus();
  }

  function clearDialogMessages() {
    if (!d) return;
    d.errorAlert.hidden = true;
    d.errorAlert.textContent = "";
    d.validationAlert.hidden = true;
    d.validationAlert.textContent = "";
    for (const el of [
      d.patientQuery,
      d.patientSelect,
      d.branchSelect,
      d.veterinarianSelect,
      d.dateInput,
      d.startTimeInput,
      d.endTimeInput,
      d.typeSelect
    ]) {
      el?.removeAttribute("aria-invalid");
    }
  }

  async function submitSearch(event) {
    event.preventDefault();
    if (!s) return;
    if (!canRead) {
      setViewState("forbidden");
      return;
    }
    const dateValue = s.dateFilter.value.trim();
    if (!isValidIsoDate(dateValue)) {
      s.dateFilter.setAttribute("aria-invalid", "true");
      s.validation.textContent = "Selecciona una fecha válida en formato YYYY-MM-DD para consultar la agenda.";
      setViewState("validation");
      s.dateFilter.focus();
      return;
    }
    s.dateFilter.removeAttribute("aria-invalid");
    await executeSearch(0);
  }

  async function executeSearch(targetPage, options = {}) {
    if (!s || searching) return false;
    if (!canRead) {
      setViewState("forbidden");
      return false;
    }
    const dateValue = s.dateFilter.value.trim();
    if (!isValidIsoDate(dateValue)) {
      s.dateFilter.setAttribute("aria-invalid", "true");
      s.validation.textContent = "Selecciona una fecha válida en formato YYYY-MM-DD para consultar la agenda.";
      setViewState("validation");
      return false;
    }
    s.dateFilter.removeAttribute("aria-invalid");

    searching = true;
    setViewState("loading");
    s.submitButton.disabled = true;

    try {
      const params = new URLSearchParams({
        date: dateValue,
        page: String(Math.max(0, targetPage)),
        size: "10",
        direction: s.directionFilter.value || "asc"
      });
      const branchId = s.branchFilter.value.trim();
      const veterinarianId = s.veterinarianFilter.value.trim();
      if (branchId) {
        params.set("branchId", branchId);
      }
      if (veterinarianId) {
        params.set("veterinarianId", veterinarianId);
      }

      const response = await client.requestJson(`/api/v1/appointments?${params.toString()}`);
      renderResults(response, dateValue);
      return true;
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 403) {
        setViewState("forbidden");
        s.forbidden.focus({ preventScroll: true });
      } else if (error instanceof ApiRequestError && error.status === 400) {
        s.validation.textContent = error.message || "Los filtros de agenda no son válidos.";
        setViewState("validation");
      } else {
        s.errorMessage.textContent = (error instanceof ApiRequestError && error.message)
          ? error.message
          : "No fue posible consultar la agenda de citas.";
        setViewState("error");
        s.error.focus({ preventScroll: true });
      }
      if (options?.throwOnError) {
        throw error;
      }
      return false;
    } finally {
      searching = false;
      s.submitButton.disabled = false;
    }
  }

  function renderResults(response, dateValue) {
    const items = Array.isArray(response?.content) ? response.content : [];
    page = Number.isInteger(response?.page) ? response.page : 0;
    totalPages = Number.isInteger(response?.totalPages) ? response.totalPages : 0;
    const totalElements = Number.isFinite(response?.totalElements) ? response.totalElements : items.length;

    renderedItemsById.clear();
    for (const item of items) {
      if (item?.id) {
        renderedItemsById.set(item.id, item);
      }
    }

    s.rows.replaceChildren();
    s.cards.replaceChildren();
    renderStatusIndicators(items, response);

    if (items.length === 0) {
      setViewState("empty");
      return;
    }

    const startItem = page * 10 + 1;
    const endItem = startItem + items.length - 1;
    s.summary.textContent = `Mostrando ${startItem}–${endItem} de ${totalElements} citas para ${dateValue}`;
    s.pagination.textContent = `Página ${page + 1} de ${Math.max(1, totalPages)}`;
    s.previousButton.disabled = page <= 0;
    s.nextButton.disabled = page + 1 >= totalPages;

    for (const item of items) {
      s.rows.append(createTableRow(item));
      s.cards.append(createMobileCard(item));
    }

    setViewState("results");
  }

  function renderStatusIndicators(items, response) {
    if (!s?.indicators) return;
    s.indicators.replaceChildren();
    let programadas = 0;
    let confirmadas = 0;
    let enEspera = 0;
    let noAsistieron = 0;
    for (const item of items) {
      if (item.status === "PROGRAMADA") programadas += 1;
      else if (item.status === "CONFIRMADA") confirmadas += 1;
      else if (item.status === "EN_ESPERA") enEspera += 1;
      else if (item.status === "NO_ASISTIO") noAsistieron += 1;
    }
    const totalProgramadas = Number.isFinite(response?.scheduledCount) ? response.scheduledCount : programadas;
    const totalConfirmadas = Number.isFinite(response?.confirmedCount) ? response.confirmedCount : confirmadas;
    const totalEnEspera = Number.isFinite(response?.waitingCount) ? response.waitingCount : enEspera;
    const totalNoShow = Number.isFinite(response?.noShowCount) ? response.noShowCount : noAsistieron;
    const chips = [
      { key: "programadas", label: `Programadas: ${totalProgramadas}` },
      { key: "confirmadas", label: `Confirmadas: ${totalConfirmadas}` },
      { key: "en-espera", label: `En espera: ${totalEnEspera}` },
      { key: "no-show", label: `No asistieron: ${totalNoShow}` }
    ];
    for (const chip of chips) {
      const span = document.createElement("span");
      span.className = "badge text-bg-light border text-dark";
      span.dataset.indicator = chip.key;
      span.textContent = chip.label;
      s.indicators.append(span);
    }
  }

  function getAvailableStatusActions(item) {
    if (!canUpdateStatus) return [];
    let actions = [];
    if (item.status === "PROGRAMADA") {
      actions = [
        { label: "Confirmar", targetStatus: "CONFIRMADA", btnClass: "btn btn-outline-primary btn-sm" },
        { label: "Registrar llegada", targetStatus: "EN_ESPERA", btnClass: "btn btn-primary btn-sm" }
      ];
    } else if (item.status === "CONFIRMADA") {
      actions = [
        { label: "Registrar llegada", targetStatus: "EN_ESPERA", btnClass: "btn btn-primary btn-sm" }
      ];
    }
    if (actions.length > 0 && Number.isFinite(Date.parse(item.endsAt)) && Date.parse(item.endsAt) <= Date.now()) {
      actions.push({ label: "Marcar inasistencia", targetStatus: "NO_ASISTIO", btnClass: "btn btn-outline-dark btn-sm" });
    }
    return actions;
  }

  function createStatusActionButtons(item) {
    if (!item.id) return [];
    const isBusy = updatingAppointmentIds.has(item.id);
    const buttons = [];

    for (const action of getAvailableStatusActions(item)) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = action.btnClass;
      btn.dataset.action = "change-appointment-status";
      btn.dataset.appointmentId = item.id;
      btn.dataset.targetStatus = action.targetStatus;
      btn.disabled = isBusy;
      btn.textContent = action.label;
      buttons.push(btn);
    }

    if (
      canStartConsultation
      && item.status === "EN_ESPERA"
      && currentUserId
      && item.veterinarianId
      && currentUserId === item.veterinarianId
    ) {
      const startConsultationBtn = document.createElement("button");
      startConsultationBtn.type = "button";
      startConsultationBtn.className = "btn btn-success btn-sm";
      startConsultationBtn.dataset.action = "start-consultation";
      startConsultationBtn.dataset.appointmentId = item.id;
      startConsultationBtn.disabled = isBusy;
      startConsultationBtn.textContent = "Iniciar atención";
      buttons.push(startConsultationBtn);
    }

    if (canReschedule && (item.status === "PROGRAMADA" || item.status === "CONFIRMADA")) {
      const rescheduleBtn = document.createElement("button");
      rescheduleBtn.type = "button";
      rescheduleBtn.className = "btn btn-outline-secondary btn-sm";
      rescheduleBtn.dataset.action = "reschedule-appointment";
      rescheduleBtn.dataset.appointmentId = item.id;
      rescheduleBtn.disabled = isBusy;
      rescheduleBtn.textContent = "Reprogramar";
      buttons.push(rescheduleBtn);
    }

    if (canCancel && (item.status === "PROGRAMADA" || item.status === "CONFIRMADA" || item.status === "EN_ESPERA")) {
      const cancelBtn = document.createElement("button");
      cancelBtn.type = "button";
      cancelBtn.className = "btn btn-outline-danger btn-sm";
      cancelBtn.dataset.action = "cancel-appointment";
      cancelBtn.dataset.appointmentId = item.id;
      cancelBtn.disabled = isBusy;
      cancelBtn.textContent = "Cancelar";
      buttons.push(cancelBtn);
    }

    return buttons;
  }

  function createTableRow(item) {
    const tr = document.createElement("tr");
    const timeZone = branchTimeZones.get(item.branchId) || "America/Lima";

    const tdTime = document.createElement("td");
    tdTime.className = "pc-data-mono fw-semibold text-nowrap";
    tdTime.textContent = formatTimeRange(item.startsAt, item.endsAt, timeZone);

    const tdPatient = document.createElement("td");
    const patientWrapper = document.createElement("div");
    patientWrapper.className = "d-flex flex-wrap align-items-center gap-2";
    const patientSpan = document.createElement("span");
    patientSpan.className = "fw-semibold";
    patientSpan.textContent = item.patientName || "Paciente";
    patientWrapper.append(patientSpan);
    if (canReadPatient && item.patientId && typeof onViewPatientProfile === "function") {
      const profileBtn = document.createElement("button");
      profileBtn.type = "button";
      profileBtn.className = "btn btn-link btn-sm p-0 text-decoration-none";
      profileBtn.dataset.action = "view-appointment-patient";
      profileBtn.dataset.patientId = item.patientId;
      profileBtn.textContent = "Ver perfil";
      patientWrapper.append(profileBtn);
    }
    tdPatient.append(patientWrapper);

    const tdType = document.createElement("td");
    tdType.textContent = formatAppointmentType(item.appointmentType);

    const tdVet = document.createElement("td");
    tdVet.textContent = item.veterinarianFullName || "—";

    const tdBranch = document.createElement("td");
    tdBranch.textContent = item.branchName || "—";

    const tdStatus = document.createElement("td");
    tdStatus.append(createStatusBadge(item.status));

    const tdActions = document.createElement("td");
    tdActions.className = "text-end";
    const statusButtons = createStatusActionButtons(item);
    if (statusButtons.length > 0) {
      const btnGroup = document.createElement("div");
      btnGroup.className = "d-inline-flex flex-wrap justify-content-end gap-1";
      btnGroup.append(...statusButtons);
      tdActions.append(btnGroup);
    } else {
      tdActions.textContent = "—";
    }

    tr.append(tdTime, tdPatient, tdType, tdVet, tdBranch, tdStatus, tdActions);
    return tr;
  }

  function createMobileCard(item) {
    const card = document.createElement("article");
    card.className = "card pc-record-card";
    const body = document.createElement("div");
    body.className = "card-body p-3";
    const timeZone = branchTimeZones.get(item.branchId) || "America/Lima";

    const topRow = document.createElement("div");
    topRow.className = "d-flex justify-content-between align-items-start gap-2 mb-2";
    const timeSpan = document.createElement("span");
    timeSpan.className = "pc-data-mono fw-semibold";
    timeSpan.textContent = formatTimeRange(item.startsAt, item.endsAt, timeZone);
    topRow.append(timeSpan, createStatusBadge(item.status));

    const patientTitle = document.createElement("h3");
    patientTitle.className = "h6 fw-semibold mb-1";
    patientTitle.textContent = item.patientName || "Paciente";

    const metaList = document.createElement("div");
    metaList.className = "small text-secondary d-grid gap-1";

    const typeLine = document.createElement("div");
    typeLine.textContent = `Tipo: ${formatAppointmentType(item.appointmentType)}`;

    const vetLine = document.createElement("div");
    vetLine.textContent = `Veterinario: ${item.veterinarianFullName || "—"}`;

    const branchLine = document.createElement("div");
    branchLine.textContent = `Sucursal: ${item.branchName || "—"}`;

    metaList.append(typeLine, vetLine, branchLine);
    body.append(topRow, patientTitle, metaList);

    const statusButtons = createStatusActionButtons(item);
    const hasProfileButton = canReadPatient && item.patientId && typeof onViewPatientProfile === "function";

    if (hasProfileButton || statusButtons.length > 0) {
      const actionsDiv = document.createElement("div");
      actionsDiv.className = "mt-2 pt-2 border-top d-flex flex-wrap justify-content-end gap-2";
      if (hasProfileButton) {
        const profileBtn = document.createElement("button");
        profileBtn.type = "button";
        profileBtn.className = "btn btn-outline-primary btn-sm";
        profileBtn.dataset.action = "view-appointment-patient";
        profileBtn.dataset.patientId = item.patientId;
        profileBtn.textContent = "Ver perfil clínico";
        actionsDiv.append(profileBtn);
      }
      actionsDiv.append(...statusButtons);
      body.append(actionsDiv);
    }

    card.append(body);
    return card;
  }

  function createStatusBadge(status) {
    const meta = STATUS_META[status] ?? {
      icon: "•",
      label: status || "Desconocido",
      badgeClass: "badge text-bg-secondary"
    };
    const span = document.createElement("span");
    span.className = meta.badgeClass;
    span.textContent = `${meta.icon} ${meta.label}`;
    return span;
  }

  async function handleDelegatedClick(event) {
    const startConsultationButton = event.target?.closest?.("[data-action='start-consultation']");
    if (startConsultationButton) {
      await handleStartConsultationClick(startConsultationButton);
      return;
    }

    const statusButton = event.target?.closest?.("[data-action='change-appointment-status']");
    if (statusButton) {
      await handleStatusChangeClick(statusButton);
      return;
    }

    const rescheduleButton = event.target?.closest?.("[data-action='reschedule-appointment']");
    if (rescheduleButton) {
      const appointmentId = rescheduleButton.dataset.appointmentId;
      const item = renderedItemsById.get(appointmentId);
      if (item) {
        await openRescheduleDialog(item);
      }
      return;
    }

    const cancelButton = event.target?.closest?.("[data-action='cancel-appointment']");
    if (cancelButton) {
      const appointmentId = cancelButton.dataset.appointmentId;
      const item = renderedItemsById.get(appointmentId);
      if (item) {
        openCancelDialog(item);
      }
      return;
    }

    const button = event.target?.closest?.("[data-action='view-appointment-patient']");
    if (!button || !canReadPatient || typeof onViewPatientProfile !== "function") return;
    const patientId = button.dataset.patientId;
    if (patientId) {
      s.view.hidden = true;
      onViewPatientProfile(patientId);
    }
  }

  async function openRescheduleDialog(item) {
    if (!r || !canReschedule || !item?.id) return;
    if (!optionsLoaded) {
      await loadOptions();
    }

    reschedulingAppointmentId = item.id;
    clearRescheduleDialogMessages();
    r.form.reset();

    r.branchSelect.replaceChildren(createOption("", "Selecciona una sucursal"));
    for (const branch of branchesCatalog) {
      r.branchSelect.append(createOption(branch.id, branch.name));
    }
    if (item.branchId) {
      r.branchSelect.value = item.branchId;
    }
    handleRescheduleBranchChange();
    if (item.veterinarianId) {
      r.veterinarianSelect.value = item.veterinarianId;
    }

    const timeZone = branchTimeZones.get(item.branchId) || "America/Lima";
    const localDate = formatLocalDateInZone(item.startsAt, timeZone) || s?.dateFilter?.value || todayLocalIsoDate();
    const localStartTime = formatTime(item.startsAt, timeZone) || "09:00";
    const localEndTime = formatTime(item.endsAt, timeZone) || "09:30";

    r.dateInput.value = localDate;
    r.startTimeInput.value = localStartTime;
    r.endTimeInput.value = localEndTime;

    if (r.summary) {
      r.summary.textContent = `${item.patientName || "Paciente"} — ${formatAppointmentType(item.appointmentType)} (${item.status === "CONFIRMADA" ? "Confirmada → pasará a Programada" : "Programada"})`;
    }

    if (typeof r.dialog.showModal === "function") {
      if (!r.dialog.open) {
        r.dialog.showModal();
      }
    } else {
      r.dialog.setAttribute("open", "");
    }
  }

  function closeRescheduleDialog() {
    if (!r || reschedulingAppointment) return;
    reschedulingAppointmentId = null;
    if (typeof r.dialog.close === "function" && r.dialog.open) {
      r.dialog.close();
    } else {
      r.dialog.removeAttribute("open");
    }
  }

  function handleRescheduleBranchChange() {
    if (!r) return;
    const branchId = r.branchSelect.value;
    const branch = branchesCatalog.find((b) => b.id === branchId);
    r.branchTimeZone.textContent = branch?.timeZone
      ? `Zona horaria: ${branch.timeZone}`
      : "Zona horaria: America/Lima";

    r.veterinarianSelect.replaceChildren(createOption("", "Selecciona un veterinario"));
    for (const vet of veterinariansCatalog) {
      const assignedIds = Array.isArray(vet.branchIds) ? vet.branchIds : [];
      if (!branchId || assignedIds.length === 0 || assignedIds.includes(branchId)) {
        r.veterinarianSelect.append(createOption(vet.id, vet.fullName));
      }
    }
  }

  function clearRescheduleDialogMessages() {
    if (!r) return;
    r.errorAlert.hidden = true;
    r.errorAlert.textContent = "";
    r.validationAlert.hidden = true;
    r.validationAlert.textContent = "";
  }

  async function submitRescheduleAppointment() {
    if (!r || !canReschedule || reschedulingAppointment || !reschedulingAppointmentId) return;
    clearRescheduleDialogMessages();

    const branchId = r.branchSelect.value.trim();
    const veterinarianId = r.veterinarianSelect.value.trim();
    const dateVal = r.dateInput.value.trim();
    const startTimeVal = r.startTimeInput.value.trim();
    const endTimeVal = r.endTimeInput.value.trim();

    const errors = [];
    if (!branchId) errors.push("Selecciona una sucursal.");
    if (!veterinarianId) errors.push("Selecciona un veterinario asignado.");
    if (!isValidIsoDate(dateVal)) errors.push("Ingresa una fecha válida (YYYY-MM-DD).");
    if (!/^\d{2}:\d{2}$/.test(startTimeVal)) errors.push("Ingresa una hora de inicio válida (HH:mm).");
    if (!/^\d{2}:\d{2}$/.test(endTimeVal)) errors.push("Ingresa una hora de fin válida (HH:mm).");
    if (/^\d{2}:\d{2}$/.test(startTimeVal) && /^\d{2}:\d{2}$/.test(endTimeVal) && endTimeVal <= startTimeVal) {
      errors.push("La hora de fin debe ser posterior a la hora de inicio.");
    }

    if (errors.length > 0) {
      r.validationAlert.textContent = errors.join(" ");
      r.validationAlert.hidden = false;
      return;
    }

    const appointmentId = reschedulingAppointmentId;
    reschedulingAppointment = true;
    updatingAppointmentIds.add(appointmentId);
    r.saveButton.disabled = true;
    r.cancelButton.disabled = true;

    try {
      const updated = await client.requestJson(`/api/v1/appointments/${appointmentId}/schedule`, {
        method: "PUT",
        body: {
          branchId,
          veterinarianId,
          startsAtLocal: `${dateVal}T${startTimeVal}`,
          endsAtLocal: `${dateVal}T${endTimeVal}`
        }
      });

      reschedulingAppointment = false;
      updatingAppointmentIds.delete(appointmentId);
      closeRescheduleDialog();

      if (s?.notice) {
        s.notice.textContent = updated?.status === "PROGRAMADA"
          ? "Cita reprogramada correctamente (estado: Programada)."
          : "Cita reprogramada correctamente.";
        s.notice.hidden = false;
      }

      if (canRead) {
        await executeSearch(page);
      }
    } catch (error) {
      const baseMessage = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible reprogramar la cita.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";
      if (error instanceof ApiRequestError && error.status === 400) {
        const fieldMsgs = Array.isArray(error.fieldErrors)
          ? error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(" ")
          : "";
        r.validationAlert.textContent = [baseMessage, fieldMsgs, correlationSuffix].filter(Boolean).join(" ").trim();
        r.validationAlert.hidden = false;
      } else {
        r.errorAlert.textContent = `${baseMessage}${correlationSuffix}`;
        r.errorAlert.hidden = false;
      }
    } finally {
      reschedulingAppointment = false;
      updatingAppointmentIds.delete(appointmentId);
      r.saveButton.disabled = false;
      r.cancelButton.disabled = false;
    }
  }

  function openCancelDialog(item) {
    if (!c || !canCancel || !item?.id) return;
    cancellingAppointmentId = item.id;
    clearCancelDialogMessages();
    c.form.reset();
    if (c.summary) {
      c.summary.textContent = `${item.patientName || "Paciente"} — ${formatAppointmentType(item.appointmentType)} (${item.veterinarianFullName || "Veterinario"})`;
    }
    if (typeof c.dialog.showModal === "function") {
      if (!c.dialog.open) {
        c.dialog.showModal();
      }
    } else {
      c.dialog.setAttribute("open", "");
    }
  }

  function closeCancelDialog() {
    if (!c || cancellingAppointment) return;
    cancellingAppointmentId = null;
    if (typeof c.dialog.close === "function" && c.dialog.open) {
      c.dialog.close();
    } else {
      c.dialog.removeAttribute("open");
    }
  }

  function clearCancelDialogMessages() {
    if (!c) return;
    c.errorAlert.hidden = true;
    c.errorAlert.textContent = "";
    c.validationAlert.hidden = true;
    c.validationAlert.textContent = "";
  }

  async function submitCancelAppointment() {
    if (!c || !canCancel || cancellingAppointment || !cancellingAppointmentId) return;
    clearCancelDialogMessages();

    const reason = c.reasonInput.value.trim();
    if (reason.length < 3 || reason.length > 500) {
      c.validationAlert.textContent = "El motivo de cancelación debe tener entre 3 y 500 caracteres.";
      c.validationAlert.hidden = false;
      return;
    }

    const appointmentId = cancellingAppointmentId;
    cancellingAppointment = true;
    updatingAppointmentIds.add(appointmentId);
    c.saveButton.disabled = true;
    c.cancelButton.disabled = true;

    try {
      await client.requestJson(`/api/v1/appointments/${appointmentId}/cancellation`, {
        method: "PUT",
        body: { reason }
      });

      cancellingAppointment = false;
      updatingAppointmentIds.delete(appointmentId);
      closeCancelDialog();

      if (s?.notice) {
        s.notice.textContent = "Cita cancelada correctamente y horario liberado.";
        s.notice.hidden = false;
      }

      if (canRead) {
        await executeSearch(page);
      }
    } catch (error) {
      const baseMessage = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible cancelar la cita.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";
      if (error instanceof ApiRequestError && error.status === 400) {
        const fieldMsgs = Array.isArray(error.fieldErrors)
          ? error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(" ")
          : "";
        c.validationAlert.textContent = [baseMessage, fieldMsgs, correlationSuffix].filter(Boolean).join(" ").trim();
        c.validationAlert.hidden = false;
      } else {
        c.errorAlert.textContent = `${baseMessage}${correlationSuffix}`;
        c.errorAlert.hidden = false;
      }
    } finally {
      cancellingAppointment = false;
      updatingAppointmentIds.delete(appointmentId);
      c.saveButton.disabled = false;
      c.cancelButton.disabled = false;
    }
  }

  async function handleStatusChangeClick(clickedButton) {
    if (!s || !canUpdateStatus) return;
    const appointmentId = clickedButton.dataset.appointmentId;
    const targetStatus = clickedButton.dataset.targetStatus;
    if (!appointmentId || !targetStatus || updatingAppointmentIds.has(appointmentId)) {
      return;
    }

    updatingAppointmentIds.add(appointmentId);
    if (s.notice) {
      s.notice.hidden = true;
    }
    if (s.statusError) {
      s.statusError.hidden = true;
      s.statusError.textContent = "";
    }

    const relatedButtons = Array.from(
      s.view.querySelectorAll(`[data-appointment-id="${appointmentId}"]`)
    );
    const originalLabels = new Map();
    for (const btn of relatedButtons) {
      originalLabels.set(btn, btn.textContent);
      btn.disabled = true;
      if (btn.dataset.action === "change-appointment-status" && btn.dataset.targetStatus === targetStatus) {
        btn.setAttribute("aria-busy", "true");
        btn.textContent = targetStatus === "CONFIRMADA"
          ? "Confirmando..."
          : targetStatus === "NO_ASISTIO" ? "Marcando inasistencia..." : "Registrando llegada...";
      }
    }

    try {
      await client.requestJson(`/api/v1/appointments/${appointmentId}/status`, {
        method: "PATCH",
        body: { status: targetStatus }
      });

      updatingAppointmentIds.delete(appointmentId);

      if (s.notice) {
        s.notice.textContent = targetStatus === "CONFIRMADA"
          ? "Cita confirmada correctamente."
          : targetStatus === "NO_ASISTIO" ? "Inasistencia registrada correctamente." : "Llegada registrada: paciente en espera.";
        s.notice.hidden = false;
      }

      if (canRead) {
        await executeSearch(page);
      }
    } catch (error) {
      updatingAppointmentIds.delete(appointmentId);
      for (const [btn, label] of originalLabels.entries()) {
        btn.disabled = false;
        btn.removeAttribute("aria-busy");
        btn.textContent = label;
      }
      const baseMessage = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible actualizar el estado de la cita.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";
      if (s.statusError) {
        s.statusError.textContent = `${baseMessage}${correlationSuffix}`;
        s.statusError.hidden = false;
        s.statusError.focus({ preventScroll: true });
      }
    } finally {
      updatingAppointmentIds.delete(appointmentId);
    }
  }

  async function handleStartConsultationClick(clickedButton) {
    if (!s || !canStartConsultation) return;
    const appointmentId = clickedButton.dataset.appointmentId;
    if (!appointmentId || updatingAppointmentIds.has(appointmentId)) {
      return;
    }

    updatingAppointmentIds.add(appointmentId);
    if (s.notice) {
      s.notice.hidden = true;
    }
    if (s.statusError) {
      s.statusError.hidden = true;
      s.statusError.textContent = "";
    }

    const item = renderedItemsById.get(appointmentId);
    const relatedButtons = Array.from(
      s.view.querySelectorAll(`[data-appointment-id="${appointmentId}"]`)
    );
    const originalLabels = new Map();
    for (const btn of relatedButtons) {
      originalLabels.set(btn, btn.textContent);
      btn.disabled = true;
      if (btn.dataset.action === "start-consultation") {
        btn.setAttribute("aria-busy", "true");
        btn.textContent = "Iniciando consulta...";
      }
    }

    let consultation = null;
    try {
      consultation = await client.requestJson(`/api/v1/appointments/${appointmentId}/consultation`, {
        method: "POST"
      });
    } catch (error) {
      updatingAppointmentIds.delete(appointmentId);
      for (const [btn, label] of originalLabels.entries()) {
        btn.disabled = false;
        btn.removeAttribute("aria-busy");
        btn.textContent = label;
      }
      const baseMessage = (error instanceof ApiRequestError && error.message)
        ? error.message
        : "No fue posible iniciar la consulta.";
      const correlationSuffix = (error instanceof ApiRequestError && error.correlationId)
        ? ` (ID de correlación: ${error.correlationId})`
        : "";
      if (s.statusError) {
        s.statusError.textContent = `${baseMessage}${correlationSuffix}`;
        s.statusError.hidden = false;
        s.statusError.focus({ preventScroll: true });
      }
      return;
    } finally {
      updatingAppointmentIds.delete(appointmentId);
    }

    if (s.notice) {
      const patientLabel = consultation?.patientName || item?.patientName || "el paciente";
      s.notice.textContent = `Consulta en borrador iniciada para ${patientLabel}. Cita en atención.`;
      s.notice.hidden = false;
    }

    if (canRead) {
      try {
        const refreshed = await executeSearch(page, { throwOnError: true });
        if (!refreshed && s.statusError && s.statusError.hidden) {
          const detail = s.errorMessage?.textContent || "No fue posible actualizar la agenda de citas.";
          s.statusError.textContent = `La consulta se inició correctamente, pero no se pudo actualizar la agenda: ${detail}`;
          s.statusError.hidden = false;
          s.statusError.focus({ preventScroll: true });
        }
      } catch (reloadError) {
        for (const [btn, label] of originalLabels.entries()) {
          btn.disabled = false;
          btn.removeAttribute("aria-busy");
          btn.textContent = label;
        }
        const reloadMessage = (reloadError instanceof ApiRequestError && reloadError.message)
          ? reloadError.message
          : (reloadError?.message || "No fue posible actualizar la agenda de citas.");
        const correlationSuffix = (reloadError instanceof ApiRequestError && reloadError.correlationId)
          ? ` (ID de correlación: ${reloadError.correlationId})`
          : "";
        if (s.statusError) {
          s.statusError.textContent = `La consulta se inició correctamente, pero no se pudo actualizar la agenda: ${reloadMessage}${correlationSuffix}`;
          s.statusError.hidden = false;
          s.statusError.focus({ preventScroll: true });
        }
      }
    }
  }

  function setViewState(activeState) {
    if (!s) return;
    s.initial.hidden = activeState !== "initial";
    s.loading.hidden = activeState !== "loading";
    s.validation.hidden = activeState !== "validation";
    s.forbidden.hidden = activeState !== "forbidden";
    s.error.hidden = activeState !== "error";
    s.empty.hidden = activeState !== "empty";
    s.results.hidden = activeState !== "results";
  }

  function createOption(value, label) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    return option;
  }
}

function formatAppointmentType(rawType) {
  if (!rawType) return "Consulta";
  return APPOINTMENT_TYPE_LABELS[rawType] || rawType.replaceAll("_", " ");
}

function formatTimeRange(startsAt, endsAt, timeZone) {
  const startText = formatTime(startsAt, timeZone);
  const endText = formatTime(endsAt, timeZone);
  return endText ? `${startText} – ${endText}` : startText;
}

function formatTime(isoInstant, timeZone) {
  if (!isoInstant) return "";
  try {
    const date = new Date(isoInstant);
    if (Number.isNaN(date.getTime())) return String(isoInstant);
    return new Intl.DateTimeFormat("es-PE", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
      timeZone: timeZone || "America/Lima"
    }).format(date);
  } catch {
    return String(isoInstant);
  }
}

function formatLocalDateInZone(isoInstant, timeZone) {
  if (!isoInstant) return "";
  try {
    const date = new Date(isoInstant);
    if (Number.isNaN(date.getTime())) return "";
    const parts = new Intl.DateTimeFormat("en-CA", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      timeZone: timeZone || "America/Lima"
    }).formatToParts(date);
    const y = parts.find((p) => p.type === "year")?.value;
    const m = parts.find((p) => p.type === "month")?.value;
    const d = parts.find((p) => p.type === "day")?.value;
    return y && m && d ? `${y}-${m}-${d}` : "";
  } catch {
    return "";
  }
}

function todayLocalIsoDate() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function isValidIsoDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const [y, m, d] = value.split("-").map(Number);
  const candidate = new Date(Date.UTC(y, m - 1, d));
  return candidate.getUTCFullYear() === y
    && candidate.getUTCMonth() === m - 1
    && candidate.getUTCDate() === d;
}

function findElements(document) {
  const view = document.querySelector("#appointments-view");
  const navButton = document.querySelector("#appointments-nav-button");
  if (!view || !navButton) {
    return null;
  }
  return {
    home: document.querySelector("#dashboard-home"),
    view,
    navButton,
    backButton: document.querySelector("#appointments-back-button"),
    title: document.querySelector("#appointments-title"),
    newAppointmentViewButton: document.querySelector("#new-appointment-view-button"),
    notice: document.querySelector("#appointments-notice"),
    statusError: document.querySelector("#appointments-status-error"),
    searchForm: document.querySelector("#appointment-search-form"),
    dateFilter: document.querySelector("#appointment-date-filter"),
    branchFilter: document.querySelector("#appointment-branch-filter"),
    veterinarianFilter: document.querySelector("#appointment-veterinarian-filter"),
    directionFilter: document.querySelector("#appointment-direction-filter"),
    submitButton: document.querySelector("#appointment-search-submit"),
    clearButton: document.querySelector("#appointment-search-clear"),
    validation: document.querySelector("#appointments-validation"),
    forbidden: document.querySelector("#appointments-forbidden"),
    error: document.querySelector("#appointments-error"),
    errorMessage: document.querySelector("#appointments-error-message"),
    retryButton: document.querySelector("#appointments-retry-button"),
    initial: document.querySelector("#appointments-initial-state"),
    loading: document.querySelector("#appointments-loading"),
    empty: document.querySelector("#appointments-empty"),
    results: document.querySelector("#appointments-results"),
    summary: document.querySelector("#appointments-summary"),
    indicators: document.querySelector("#appointments-status-indicators"),
    rows: document.querySelector("#appointments-table-body"),
    cards: document.querySelector("#appointments-cards"),
    pagination: document.querySelector("#appointments-pagination"),
    previousButton: document.querySelector("#appointments-previous-page"),
    nextButton: document.querySelector("#appointments-next-page")
  };
}

function findDialogElements(document) {
  const dialog = document.querySelector("#appointment-editor-dialog");
  if (!dialog) {
    return null;
  }
  return {
    dialog,
    form: document.querySelector("#appointment-editor-form"),
    errorAlert: document.querySelector("#appointment-form-error"),
    validationAlert: document.querySelector("#appointment-form-validation"),
    patientSearchGroup: document.querySelector("#appointment-patient-search-group"),
    patientQuery: document.querySelector("#appointment-patient-query"),
    patientSearchButton: document.querySelector("#appointment-patient-search-btn"),
    patientSelect: document.querySelector("#appointment-patient-id"),
    branchSelect: document.querySelector("#appointment-branch-id"),
    branchTimeZone: document.querySelector("#appointment-branch-timezone"),
    veterinarianSelect: document.querySelector("#appointment-veterinarian-id"),
    dateInput: document.querySelector("#appointment-date"),
    startTimeInput: document.querySelector("#appointment-start-time"),
    endTimeInput: document.querySelector("#appointment-end-time"),
    typeSelect: document.querySelector("#appointment-type"),
    reasonInput: document.querySelector("#appointment-reason"),
    notesInput: document.querySelector("#appointment-notes"),
    cancelButton: document.querySelector("#cancel-appointment-button"),
    saveButton: document.querySelector("#save-appointment-button")
  };
}

function findRescheduleDialogElements(document) {
  const dialog = document.querySelector("#appointment-reschedule-dialog");
  if (!dialog) {
    return null;
  }
  const form = document.querySelector("#appointment-reschedule-form");
  const branchSelect = document.querySelector("#reschedule-branch-id, #appointment-reschedule-branch-id");
  const branchTimeZone = document.querySelector("#reschedule-branch-timezone, #appointment-reschedule-branch-timezone");
  const veterinarianSelect = document.querySelector("#reschedule-veterinarian-id, #appointment-reschedule-veterinarian-id");
  const dateInput = document.querySelector("#reschedule-date, #appointment-reschedule-date");
  const startTimeInput = document.querySelector("#reschedule-start-time, #appointment-reschedule-start-time");
  const endTimeInput = document.querySelector("#reschedule-end-time, #appointment-reschedule-end-time");
  const cancelButton = document.querySelector("#cancel-reschedule-button, #cancel-reschedule-appointment-button");
  const saveButton = document.querySelector("#save-reschedule-button, #save-reschedule-appointment-button");
  const summary = document.querySelector("#appointment-reschedule-summary");
  const errorAlert = document.querySelector("#appointment-reschedule-error");
  const validationAlert = document.querySelector("#appointment-reschedule-validation");

  if (
    !form
    || !cancelButton
    || !saveButton
    || !branchSelect
    || !veterinarianSelect
    || !dateInput
    || !startTimeInput
    || !endTimeInput
  ) {
    return null;
  }

  return {
    dialog,
    form,
    summary,
    errorAlert,
    validationAlert,
    branchSelect,
    branchTimeZone,
    veterinarianSelect,
    dateInput,
    startTimeInput,
    endTimeInput,
    cancelButton,
    saveButton
  };
}

function findCancelDialogElements(document) {
  const dialog = document.querySelector("#appointment-cancel-dialog");
  if (!dialog) {
    return null;
  }
  return {
    dialog,
    form: document.querySelector("#appointment-cancel-form"),
    summary: document.querySelector("#appointment-cancel-summary"),
    errorAlert: document.querySelector("#appointment-cancel-error"),
    validationAlert: document.querySelector("#appointment-cancel-validation"),
    reasonInput: document.querySelector("#appointment-cancel-reason"),
    cancelButton: document.querySelector("#dismiss-cancel-appointment-button"),
    saveButton: document.querySelector("#confirm-cancel-appointment-button")
  };
}
