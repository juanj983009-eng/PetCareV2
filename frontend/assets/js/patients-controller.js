import { ApiRequestError } from "./api-client.js";

const PATIENT_CREATE = "PATIENT_CREATE";
const PATIENT_READ = "PATIENT_READ";

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

export function createPatientsController({ document, client }) {
  const e = elements(document);
  const s = searchElements(document);
  let canCreate = false;
  let canRead = false;
  let loading = false;
  let searching = false;
  let page = 0;
  let totalPages = 0;

  e.open.addEventListener("click", openCreateDialog);
  e.cancel.addEventListener("click", () => e.dialog.close());
  e.ownerSearch.addEventListener("click", findOwners);
  e.form.addEventListener("submit", create);

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

  return Object.freeze({
    sessionReady(identity) {
      const authorities = new Set(identity?.authorities ?? []);
      canCreate = authorities.has(PATIENT_CREATE);
      canRead = authorities.has(PATIENT_READ);
      e.open.hidden = !canCreate;
      if (s) {
        s.navButton.hidden = !canRead;
        if (s.newPatientViewButton) {
          s.newPatientViewButton.hidden = !canCreate;
        }
        closeSearchView();
        resetSearchState();
      }
    },
    sessionEnded() {
      canCreate = false;
      canRead = false;
      e.open.hidden = true;
      if (e.dialog.open) {
        e.dialog.close();
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
    openSearchView
  });

  function openSearchView() {
    if (!s) return;
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
    s.view.hidden = true;
    s.home.hidden = false;
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
      actionCell.append(createProfileSlot(document, patientId));

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
      cardFooter.append(createProfileSlot(document, patientId));

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

  function openCreateDialog() {
    if (!canCreate) return;
    e.form.reset();
    e.form.classList.remove("was-validated");
    e.error.hidden = true;
    e.validation.hidden = true;
    e.ownerOptions.replaceChildren(new Option("Busca un propietario", ""));
    e.dialog.showModal();
    e.ownerQuery.focus();
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
      e.ownerOptions.replaceChildren(
        new Option("Selecciona un propietario", ""),
        ...result.content.map((owner) => new Option(`${owner.firstName} ${owner.lastName} · ${owner.phone}`, owner.id))
      );
      e.error.hidden = true;
    } catch (error) {
      e.error.textContent = error instanceof ApiRequestError ? error.message : "No fue posible buscar propietarios.";
      e.error.hidden = false;
    } finally {
      e.ownerSearch.disabled = false;
    }
  }

  async function create(event) {
    event.preventDefault();
    if (loading || !canCreate) return;
    e.form.classList.add("was-validated");
    const invalid = [...e.form.querySelectorAll("input,select")].filter((input) => !input.checkValidity());
    for (const input of e.form.querySelectorAll("input,select")) {
      if (invalid.includes(input)) input.setAttribute("aria-invalid", "true");
      else input.removeAttribute("aria-invalid");
    }
    if (invalid.length) {
      e.validation.textContent = "Corrige los campos indicados antes de registrar el paciente.";
      e.validation.hidden = false;
      invalid[0].focus();
      return;
    }
    loading = true;
    e.save.disabled = true;
    e.error.hidden = true;
    try {
      const created = await client.requestJson("/api/v1/patients", {
        method: "POST",
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
      e.dialog.close();
      e.notice.hidden = false;
      e.notice.focus();
      if (s && canRead && !s.view.hidden && created?.name) {
        s.queryInput.value = created.name;
        totalPages = 0;
        await executeSearch(0);
      }
    } catch (error) {
      e.error.textContent = error instanceof ApiRequestError ? error.message : "No fue posible registrar el paciente.";
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

function createProfileSlot(document, patientId) {
  const slot = document.createElement("span");
  slot.className = "badge text-bg-light border text-secondary";
  slot.dataset.patientProfileSlot = "true";
  slot.dataset.patientId = patientId;
  slot.setAttribute("aria-disabled", "true");
  slot.textContent = "Ficha clínica (HU-013)";
  return slot;
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
