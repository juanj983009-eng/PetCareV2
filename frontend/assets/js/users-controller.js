import { ApiRequestError } from "./api-client.js";

const USER_READ = "USER_READ";
const USER_MANAGE = "USER_MANAGE";

export function createUsersController({ document, client, errors }) {
  const elements = findElements(document);
  let canRead = false;
  let canManage = false;
  let loaded = false;
  let loading = false;
  let listRequestId = 0;
  let currentPage = 0;
  let totalPages = 0;
  let editingUser = null;
  let pendingStatusChange = null;
  let editorSubmitting = false;
  let statusSubmitting = false;

  elements.openButton.addEventListener("click", openUsers);
  elements.backButton.addEventListener("click", showDashboard);
  elements.filters.addEventListener("submit", applyFilters);
  elements.filters.addEventListener("click", applyQuickStatus);
  elements.clearFilters.addEventListener("click", clearFilters);
  elements.retryList.addEventListener("click", () => loadPage(currentPage));
  elements.previousPage.addEventListener("click", () => loadPage(currentPage - 1));
  elements.nextPage.addEventListener("click", () => loadPage(currentPage + 1));
  elements.newButton.addEventListener("click", () => openEditor("create"));
  elements.form.addEventListener("submit", submitEditor);
  elements.cancelEditor.addEventListener("click", closeEditor);
  elements.cancelStatus.addEventListener("click", closeStatusDialog);
  elements.confirmStatus.addEventListener("click", confirmStatusChange);

  return Object.freeze({ sessionReady, sessionEnded, destroy });

  function sessionReady(identity) {
    const authorities = new Set(identity?.authorities ?? []);
    canRead = authorities.has(USER_READ);
    canManage = canRead && authorities.has(USER_MANAGE);
    elements.openButton.hidden = !canRead;
    elements.newButton.hidden = !canManage;
    showDashboard();
  }

  function sessionEnded() {
    listRequestId += 1;
    loading = false;
    canRead = false;
    canManage = false;
    loaded = false;
    editingUser = null;
    pendingStatusChange = null;
    elements.openButton.hidden = true;
    elements.usersView.hidden = true;
    elements.homeView.hidden = false;
    elements.rows.replaceChildren();
    elements.cards.replaceChildren();
    const overview = document.querySelector("#users-overview");
    if (overview) overview.hidden = true;
    closeDialog(elements.editorDialog);
    closeDialog(elements.statusDialog);
  }

  async function openUsers() {
    if (!canRead) {
      return;
    }
    elements.homeView.hidden = true;
    elements.usersView.hidden = false;
    elements.usersTitle.focus({ preventScroll: true });
    if (!loaded) {
      showListState("initial");
      await loadOptions();
    }
  }

  function showDashboard() {
    elements.usersView.hidden = true;
    elements.homeView.hidden = false;
  }

  async function loadOptions() {
    try {
      const value = await client.requestJson("/api/v1/users/options");
      renderOptions(value);
    } catch (error) {
      showListError(error);
    }
  }

  async function loadPage(page) {
    if (loading || page < 0 || (totalPages > 0 && page >= totalPages)) {
      return;
    }
    loading = true;
    const requestId = ++listRequestId;
    showListState("loading");
    try {
      const query = new URLSearchParams({
        page: String(page),
        size: "6",
        sortBy: elements.sort.value,
        direction: elements.direction.value
      });
      appendIfPresent(query, "search", elements.search.value.trim());
      appendIfPresent(query, "roleId", elements.roleFilter.value);
      appendIfPresent(query, "status", elements.statusFilter.value);
      const result = validPage(await client.requestJson(`/api/v1/users?${query}`));
      if (requestId !== listRequestId || !canRead) return;
      currentPage = result.page;
      totalPages = result.totalPages;
      loaded = true;
      renderUsers(result.content);
      updatePagination(result);
      renderOverview(result);
      showListState(result.content.length === 0 ? "empty" : "content");
    } catch (error) {
      if (requestId === listRequestId && canRead) showListError(error);
    } finally {
      if (requestId === listRequestId) loading = false;
    }
  }

  function applyFilters(event) {
    event.preventDefault();
    totalPages = 0;
    loadPage(0);
  }

  function clearFilters() {
    listRequestId += 1;
    loading = false;
    loaded = false;
    elements.filters.reset();
    totalPages = 0;
    elements.rows.replaceChildren();
    elements.cards.replaceChildren();
    showListState("initial");
  }

  function applyQuickStatus(event) {
    const button = event.target.closest?.("button[data-user-status]");
    if (!button || !elements.filters.contains(button) || !canRead || loading) return;
    elements.statusFilter.value = button.dataset.userStatus;
    totalPages = 0;
    loadPage(0);
  }

  function renderOverview(page) {
    const values = {
      "users-summary-total": page.totalElements,
      "users-summary-active": page.content.filter(user => user.status === "ACTIVO").length,
      "users-summary-inactive": page.content.filter(user => user.status !== "ACTIVO").length,
      "users-summary-branches": new Set(page.content.map(user => user.branch?.id).filter(Boolean)).size
    };
    for (const [id, value] of Object.entries(values)) {
      const element = document.getElementById(id);
      if (element) element.textContent = String(value);
    }
    for (const button of elements.filters.querySelectorAll("[data-user-status]")) {
      button.setAttribute("aria-pressed", String(button.dataset.userStatus === elements.statusFilter.value));
    }
  }

  async function openEditor(mode, userId = null) {
    if (mode !== "view" && !canManage) {
      return;
    }
    errors.clearApiErrors(elements.form, elements.formErrors);
    elements.form.reset();
    elements.form.classList.remove("was-validated");
    setEditorLoading(true);
    openDialog(elements.editorDialog);
    try {
      editingUser = userId === null ? null : validUser(await client.requestJson(`/api/v1/users/${userId}`));
      populateEditor(mode, editingUser);
    } catch (error) {
      closeEditor();
      showListError(error);
    } finally {
      setEditorLoading(false);
    }
  }

  function populateEditor(mode, user) {
    const readOnly = mode === "view";
    elements.editorTitle.textContent = user === null
      ? "Registrar usuario"
      : readOnly ? "Detalle del usuario" : "Editar usuario";
    elements.passwordGroup.hidden = user !== null;
    elements.initialPassword.required = user === null;
    elements.initialPassword.value = "";
    elements.firstName.value = user?.firstName ?? "";
    elements.lastName.value = user?.lastName ?? "";
    elements.email.value = user?.email ?? "";
    elements.role.value = user?.role?.id ?? "";
    elements.branch.value = user?.branch?.id ?? "";
    elements.version.value = String(user?.version ?? 0);
    for (const control of [elements.firstName, elements.lastName, elements.email, elements.role, elements.branch]) {
      control.disabled = readOnly;
    }
    elements.submitEditor.hidden = readOnly;
    elements.cancelEditor.textContent = readOnly ? "Cerrar" : "Cancelar";
    elements.firstName.focus({ preventScroll: true });
  }

  async function submitEditor(event) {
    event.preventDefault();
    if (!canManage || editorSubmitting) {
      return;
    }
    errors.clearApiErrors(elements.form, elements.formErrors);
    elements.form.classList.add("was-validated");
    if (!elements.form.checkValidity()) {
      elements.form.querySelector(":invalid")?.focus();
      return;
    }
    const creating = editingUser === null;
    const payload = {
      firstName: elements.firstName.value,
      lastName: elements.lastName.value,
      email: elements.email.value,
      roleId: elements.role.value,
      branchId: elements.branch.value
    };
    if (creating) {
      payload.initialPassword = elements.initialPassword.value;
    } else {
      payload.version = Number(elements.version.value);
    }
    editorSubmitting = true;
    setEditorLoading(true);
    try {
      await client.requestJson(creating ? "/api/v1/users" : `/api/v1/users/${editingUser.id}`, {
        method: creating ? "POST" : "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      elements.initialPassword.value = "";
      closeEditor();
      await loadPage(currentPage);
      showNotice(creating ? "Usuario registrado correctamente." : "Usuario actualizado correctamente.");
    } catch (error) {
      const normalized = normalizeError(error);
      const summary = errors.applyApiError(elements.form, normalized, elements.formErrors);
      summary.tabIndex = -1;
      summary.focus();
    } finally {
      editorSubmitting = false;
      setEditorLoading(false);
    }
  }

  function requestStatusChange(user) {
    if (!canManage || user.status === "BLOQUEADO") {
      return;
    }
    const activate = user.status === "INACTIVO";
    pendingStatusChange = { user, action: activate ? "activate" : "deactivate" };
    elements.statusTitle.textContent = activate ? "Activar usuario" : "Desactivar usuario";
    elements.statusMessage.textContent = activate
      ? `¿Deseas activar a ${user.firstName} ${user.lastName}?`
      : `¿Deseas desactivar a ${user.firstName} ${user.lastName}? No podrá iniciar nuevas sesiones.`;
    elements.confirmStatus.textContent = activate ? "Activar" : "Desactivar";
    elements.confirmStatus.className = activate ? "btn btn-success" : "btn btn-danger";
    openDialog(elements.statusDialog);
    elements.confirmStatus.focus({ preventScroll: true });
  }

  async function confirmStatusChange() {
    if (pendingStatusChange === null || statusSubmitting) {
      return;
    }
    const { user, action } = pendingStatusChange;
    statusSubmitting = true;
    elements.confirmStatus.disabled = true;
    try {
      await client.requestJson(`/api/v1/users/${user.id}/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ version: user.version })
      });
      closeStatusDialog();
      await loadPage(currentPage);
      showNotice(action === "activate" ? "Usuario activado." : "Usuario desactivado.");
    } catch (error) {
      closeStatusDialog();
      showListError(error);
    } finally {
      statusSubmitting = false;
      elements.confirmStatus.disabled = false;
    }
  }

  function closeEditor() {
    elements.initialPassword.value = "";
    editingUser = null;
    closeDialog(elements.editorDialog);
  }

  function closeStatusDialog() {
    pendingStatusChange = null;
    closeDialog(elements.statusDialog);
  }

  function renderOptions(value) {
    const roles = Array.isArray(value?.roles) ? value.roles : [];
    const branches = Array.isArray(value?.branches) ? value.branches : [];
    replaceSelectOptions(elements.roleFilter, roles, "Todos los roles");
    replaceSelectOptions(elements.role, roles, "Selecciona un rol");
    replaceSelectOptions(elements.branch, branches.filter((branch) => branch.active), "Selecciona una sede");
  }

  function renderUsers(users) {
    elements.rows.replaceChildren();
    elements.cards.replaceChildren();
    for (const user of users) {
      elements.rows.append(createTableRow(user));
      elements.cards.append(createMobileCard(user));
    }
  }

  function createTableRow(user) {
    const row = document.createElement("tr");
    const name = document.createElement("td");
    const identity = document.createElement("div");
    identity.className = "pc-user-identity";
    const avatar = document.createElement("span");
    avatar.className = "pc-user-avatar";
    avatar.setAttribute("aria-hidden", "true");
    avatar.textContent = [user.firstName, user.lastName].map(value => Array.from(value.trim())[0] ?? "").join("").toLocaleUpperCase("es");
    const label = document.createElement("strong");
    label.textContent = fullName(user);
    identity.append(avatar, label);
    name.append(identity);
    const role = document.createElement("td");
    const roleBadge = document.createElement("span");
    roleBadge.className = `pc-user-role ${user.role?.name === "ADMINISTRADOR" ? "pc-user-role-admin" : user.role?.name === "RECEPCIONISTA" ? "pc-user-role-reception" : ""}`.trim();
    roleBadge.textContent = user.role?.name ?? "Sin rol";
    role.append(roleBadge);
    row.append(name, textCell(user.email), role, textCell(user.branch?.name ?? "Sin sede"));
    const status = document.createElement("td");
    status.append(statusBadge(user.status));
    row.append(status);
    const actions = document.createElement("td");
    actions.className = "text-end text-nowrap";
    actions.append(actionButton(canManage ? "Editar" : "Ver", () => openEditor(canManage ? "edit" : "view", user.id)));
    if (canManage && user.status !== "BLOQUEADO") {
      actions.append(actionButton(user.status === "INACTIVO" ? "Activar" : "Desactivar", () => requestStatusChange(user), user.status === "INACTIVO" ? "ms-2 pc-user-activate" : "ms-2 pc-user-deactivate"));
    }
    row.append(actions);
    return row;
  }

  function createMobileCard(user) {
    const article = document.createElement("article");
    article.className = "card user-card";
    const body = document.createElement("div");
    body.className = "card-body";
    const heading = document.createElement("h3");
    heading.className = "h6 mb-1";
    heading.textContent = fullName(user);
    const identity = document.createElement("div");
    identity.className = "pc-user-mobile-identity";
    const avatar = document.createElement("span");
    avatar.className = "pc-user-avatar";
    avatar.setAttribute("aria-hidden", "true");
    avatar.textContent = [user.firstName, user.lastName].map(value => Array.from(value.trim())[0] ?? "").join("").toLocaleUpperCase("es");
    identity.append(avatar, heading);
    const email = document.createElement("p");
    email.className = "small text-secondary text-break mb-3";
    email.textContent = user.email;
    const details = document.createElement("p");
    details.className = "small mb-3";
    details.textContent = `${user.role?.name ?? "Sin rol"} · ${user.branch?.name ?? "Sin sede"}`;
    const controls = document.createElement("div");
    controls.className = "d-flex flex-wrap align-items-center gap-2";
    controls.append(statusBadge(user.status));
    controls.append(actionButton(canManage ? "Editar" : "Ver", () => openEditor(canManage ? "edit" : "view", user.id)));
    if (canManage && user.status !== "BLOQUEADO") {
      controls.append(actionButton(user.status === "INACTIVO" ? "Activar" : "Desactivar", () => requestStatusChange(user)));
    }
    body.append(identity, email, details, controls);
    article.append(body);
    return article;
  }

  function showListState(state) {
    const initial = document.querySelector("#users-initial");
    if (initial) initial.hidden = state !== "initial";
    if (state === "initial") {
      for (const id of ["users-summary-total", "users-summary-active", "users-summary-inactive", "users-summary-branches"]) {
        const metric = document.getElementById(id);
        if (metric) metric.textContent = "—";
      }
    }
    const overview = document.querySelector("#users-overview");
    if (overview) overview.hidden = state === "loading";
    elements.loading.hidden = state !== "loading";
    elements.empty.hidden = state !== "empty";
    elements.content.hidden = state !== "content";
    elements.listError.hidden = true;
  }

  function showListError(error) {
    const overview = document.querySelector("#users-overview");
    if (overview) overview.hidden = true;
    const normalized = normalizeError(error);
    elements.loading.hidden = true;
    elements.empty.hidden = true;
    elements.content.hidden = true;
    elements.listError.hidden = false;
    elements.listErrorMessage.textContent = normalized.message;
    elements.listErrorCorrelation.textContent = normalized.correlationId === null
      ? ""
      : `Código de seguimiento: ${normalized.correlationId}`;
    elements.listErrorCorrelation.hidden = normalized.correlationId === null;
  }

  function showNotice(message) {
    elements.notice.textContent = message;
    elements.notice.hidden = false;
    elements.notice.focus({ preventScroll: true });
  }

  function updatePagination(page) {
    const resultCount = document.querySelector("#users-result-count");
    if (resultCount) resultCount.textContent = `${page.totalElements} ${page.totalElements === 1 ? "usuario" : "usuarios"}`;
    elements.pageStatus.textContent = page.totalPages === 0
      ? "Sin páginas"
      : `Página ${page.page + 1} de ${page.totalPages} · ${page.totalElements} usuarios`;
    elements.previousPage.disabled = page.page <= 0;
    elements.nextPage.disabled = page.page + 1 >= page.totalPages;
  }

  function setEditorLoading(value) {
    elements.submitEditor.disabled = value;
    elements.submitEditor.setAttribute("aria-busy", String(value));
  }

  function destroy() {
    elements.filters.removeEventListener("click", applyQuickStatus);
    sessionEnded();
  }
}

function findElements(document) {
  const selectors = {
    homeView: "#dashboard-home",
    usersView: "#users-view",
    usersTitle: "#users-title",
    openButton: "#users-nav-button",
    backButton: "#users-back-button",
    newButton: "#new-user-button",
    filters: "#user-filters",
    search: "#user-search",
    roleFilter: "#user-role-filter",
    statusFilter: "#user-status-filter",
    sort: "#user-sort",
    direction: "#user-direction",
    clearFilters: "#clear-user-filters",
    loading: "#users-loading",
    empty: "#users-empty",
    content: "#users-content",
    rows: "#users-table-body",
    cards: "#users-cards",
    listError: "#users-error",
    listErrorMessage: "#users-error-message",
    listErrorCorrelation: "#users-error-correlation",
    retryList: "#retry-users-list",
    notice: "#users-notice",
    previousPage: "#users-previous-page",
    nextPage: "#users-next-page",
    pageStatus: "#users-page-status",
    editorDialog: "#user-editor-dialog",
    editorTitle: "#user-editor-title",
    form: "#user-form",
    formErrors: "#user-form-errors",
    firstName: "#user-first-name",
    lastName: "#user-last-name",
    email: "#user-email",
    passwordGroup: "#user-password-group",
    initialPassword: "#user-initial-password",
    role: "#user-role",
    branch: "#user-branch",
    version: "#user-version",
    submitEditor: "#save-user-button",
    cancelEditor: "#cancel-user-button",
    statusDialog: "#user-status-dialog",
    statusTitle: "#user-status-title",
    statusMessage: "#user-status-message",
    confirmStatus: "#confirm-user-status",
    cancelStatus: "#cancel-user-status"
  };
  const elements = {};
  for (const [name, selector] of Object.entries(selectors)) {
    const element = document.querySelector(selector);
    if (element === null) {
      throw new Error(`No se encontró el elemento requerido: ${name}`);
    }
    elements[name] = element;
  }
  return elements;
}

function validPage(value) {
  if (!value || !Array.isArray(value.content) || !Number.isInteger(value.page)
      || !Number.isInteger(value.totalPages) || typeof value.totalElements !== "number") {
    throw invalidResponse();
  }
  return { ...value, content: value.content.map(validUser) };
}

function validUser(value) {
  if (!value || typeof value.id !== "string" || typeof value.firstName !== "string"
      || typeof value.lastName !== "string" || typeof value.email !== "string"
      || typeof value.status !== "string" || !Number.isInteger(value.version)) {
    throw invalidResponse();
  }
  return value;
}

function invalidResponse() {
  return new ApiRequestError({
    kind: "INVALID_RESPONSE",
    code: "INVALID_RESPONSE",
    message: "El servidor devolvió una respuesta no válida."
  });
}

function normalizeError(error) {
  return error instanceof ApiRequestError ? error : new ApiRequestError({
    kind: "INVALID_RESPONSE",
    code: "INVALID_RESPONSE",
    message: "No fue posible completar la operación."
  });
}

function replaceSelectOptions(select, values, placeholder) {
  const current = select.value;
  select.replaceChildren();
  const empty = document.createElement("option");
  empty.value = "";
  empty.textContent = placeholder;
  select.append(empty);
  for (const value of values) {
    const option = document.createElement("option");
    option.value = value.id;
    option.textContent = value.name;
    select.append(option);
  }
  if (Array.from(select.options).some((option) => option.value === current)) {
    select.value = current;
  }
}

function textCell(value) {
  const cell = document.createElement("td");
  cell.textContent = value;
  return cell;
}

function statusBadge(status) {
  const badge = document.createElement("span");
  badge.className = `badge ${status === "ACTIVO" ? "text-bg-success" : status === "INACTIVO" ? "text-bg-secondary" : "text-bg-warning"}`;
  badge.textContent = status === "ACTIVO" ? "● Activo" : status === "INACTIVO" ? "○ Inactivo" : "▲ Bloqueado";
  return badge;
}

function actionButton(label, action, extraClass = "") {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `btn btn-sm btn-outline-primary ${extraClass}`.trim();
  button.textContent = label;
  button.addEventListener("click", action);
  return button;
}

function fullName(user) {
  return `${user.firstName} ${user.lastName}`.trim();
}

function appendIfPresent(query, key, value) {
  if (value !== "") {
    query.set(key, value);
  }
}

function openDialog(dialog) {
  if (!dialog.open) {
    dialog.showModal();
  }
}

function closeDialog(dialog) {
  if (dialog.open) {
    dialog.close();
  }
}
