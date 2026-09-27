import { ApiRequestError } from "./api-client.js";

const APPOINTMENT_READ = "APPOINTMENT_READ";
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

export function createAppointmentsController({ document, client, onViewPatientProfile = null }) {
  const s = findElements(document);
  let canRead = false;
  let canReadPatient = false;
  let searching = false;
  let optionsLoaded = false;
  let page = 0;
  let totalPages = 0;
  const branchTimeZones = new Map();

  if (s) {
    s.navButton.addEventListener("click", openAppointmentsView);
    s.backButton.addEventListener("click", closeAppointmentsView);
    s.searchForm.addEventListener("submit", submitSearch);
    s.clearButton.addEventListener("click", clearFilters);
    s.retryButton.addEventListener("click", () => executeSearch(page));
    s.previousButton.addEventListener("click", () => executeSearch(page - 1));
    s.nextButton.addEventListener("click", () => executeSearch(page + 1));
    s.view.addEventListener("click", handleDelegatedClick);
  }

  return Object.freeze({
    sessionReady(identity) {
      const authorities = new Set(identity?.authorities ?? []);
      canRead = authorities.has(APPOINTMENT_READ);
      canReadPatient = authorities.has(PATIENT_READ);
      optionsLoaded = false;
      branchTimeZones.clear();
      if (s) {
        s.navButton.hidden = !canRead;
        if (s.newAppointmentViewButton) {
          s.newAppointmentViewButton.hidden = true;
          s.newAppointmentViewButton.disabled = true;
        }
        closeAppointmentsView();
        resetStateToDefaultDate();
      }
    },
    sessionEnded() {
      canRead = false;
      canReadPatient = false;
      optionsLoaded = false;
      branchTimeZones.clear();
      if (s) {
        s.navButton.hidden = true;
        if (s.newAppointmentViewButton) {
          s.newAppointmentViewButton.hidden = true;
          s.newAppointmentViewButton.disabled = true;
        }
        s.view.hidden = true;
        s.home.hidden = false;
        resetStateToDefaultDate();
      }
    },
    openAppointmentsView
  });

  async function openAppointmentsView() {
    if (!s) return;
    s.home.hidden = true;
    s.view.hidden = false;
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
    if (canRead) {
      await executeSearch(0);
    } else {
      setViewState("initial");
    }
  }

  async function loadOptions() {
    if (!s || !canRead) return;
    try {
      const response = await client.requestJson("/api/v1/appointments/options");
      branchTimeZones.clear();
      const branchOptions = [createOption("", "Todas las sucursales")];
      for (const branch of Array.isArray(response?.branches) ? response.branches : []) {
        if (branch?.id && branch?.name) {
          branchTimeZones.set(branch.id, branch.timeZone || "America/Lima");
          branchOptions.push(createOption(branch.id, branch.name));
        }
      }
      s.branchFilter.replaceChildren(...branchOptions);

      const vetOptions = [createOption("", "Todos los veterinarios")];
      for (const vet of Array.isArray(response?.veterinarians) ? response.veterinarians : []) {
        if (vet?.id && vet?.fullName) {
          vetOptions.push(createOption(vet.id, vet.fullName));
        }
      }
      s.veterinarianFilter.replaceChildren(...vetOptions);
      optionsLoaded = true;
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 403) {
        setViewState("forbidden");
      }
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

  async function executeSearch(targetPage) {
    if (!s || searching) return;
    if (!canRead) {
      setViewState("forbidden");
      return;
    }
    const dateValue = s.dateFilter.value.trim();
    if (!isValidIsoDate(dateValue)) {
      s.dateFilter.setAttribute("aria-invalid", "true");
      s.validation.textContent = "Selecciona una fecha válida en formato YYYY-MM-DD para consultar la agenda.";
      setViewState("validation");
      return;
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

    s.rows.replaceChildren();
    s.cards.replaceChildren();

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

    tr.append(tdTime, tdPatient, tdType, tdVet, tdBranch, tdStatus);
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

    if (canReadPatient && item.patientId && typeof onViewPatientProfile === "function") {
      const actionsDiv = document.createElement("div");
      actionsDiv.className = "mt-2 pt-2 border-top d-flex justify-content-end";
      const profileBtn = document.createElement("button");
      profileBtn.type = "button";
      profileBtn.className = "btn btn-outline-primary btn-sm";
      profileBtn.dataset.action = "view-appointment-patient";
      profileBtn.dataset.patientId = item.patientId;
      profileBtn.textContent = "Ver perfil clínico";
      actionsDiv.append(profileBtn);
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

  function handleDelegatedClick(event) {
    const button = event.target?.closest?.("[data-action='view-appointment-patient']");
    if (!button || !canReadPatient || typeof onViewPatientProfile !== "function") return;
    const patientId = button.dataset.patientId;
    if (patientId) {
      s.view.hidden = true;
      onViewPatientProfile(patientId);
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
    rows: document.querySelector("#appointments-table-body"),
    cards: document.querySelector("#appointments-cards"),
    pagination: document.querySelector("#appointments-pagination"),
    previousButton: document.querySelector("#appointments-previous-page"),
    nextButton: document.querySelector("#appointments-next-page")
  };
}
