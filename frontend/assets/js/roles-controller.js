import { ApiRequestError } from "./api-client.js";

const ROLE_READ = "ROLE_READ";
const ROLE_MANAGE = "ROLE_MANAGE";

export function createRolesController({ document, client }) {
  const elements = required(document);
  let catalog = null;
  let canRead = false;
  let canManage = false;
  let userPage = null;

  elements.open.addEventListener("click", openRoles);
  elements.back.addEventListener("click", showDashboard);
  elements.retry.addEventListener("click", loadCatalog);
  elements.assign.addEventListener("click", openAssignment);
  elements.cancelPermissions.addEventListener("click", () => close(elements.permissionsDialog));
  elements.savePermissions.addEventListener("click", savePermissions);
  elements.cancelAssignment.addEventListener("click", () => close(elements.assignmentDialog));
  elements.saveAssignment.addEventListener("click", saveAssignment);
  elements.assignmentUser.addEventListener("change", synchronizeAssignmentRoles);

  return Object.freeze({ sessionReady, sessionEnded });

  function sessionReady(identity) {
    const authorities = new Set(identity?.authorities ?? []);
    canRead = authorities.has(ROLE_READ);
    canManage = canRead && authorities.has(ROLE_MANAGE);
    elements.open.hidden = !canRead;
    elements.assign.hidden = !canManage;
    showDashboard();
  }

  function sessionEnded() {
    canRead = false;
    canManage = false;
    catalog = null;
    userPage = null;
    elements.open.hidden = true;
    elements.rolesView.hidden = true;
    elements.home.hidden = false;
    elements.list.replaceChildren();
    close(elements.permissionsDialog);
    close(elements.assignmentDialog);
  }

  async function openRoles() {
    if (!canRead) return;
    elements.home.hidden = true;
    elements.rolesView.hidden = false;
    elements.title.focus({ preventScroll: true });
    await loadCatalog();
  }

  function showDashboard() {
    elements.rolesView.hidden = true;
    elements.home.hidden = false;
  }

  async function loadCatalog() {
    state("loading");
    try {
      catalog = validCatalog(await client.requestJson("/api/v1/roles"));
      renderRoles(catalog.roles);
      state(catalog.roles.length === 0 ? "empty" : "content");
    } catch (error) {
      showError(error);
    }
  }

  function renderRoles(roles) {
    elements.list.replaceChildren();
    for (const role of roles) {
      const article = document.createElement("article");
      article.className = "col-12 col-md-6";
      const card = document.createElement("div");
      card.className = "card h-100 pc-record-card";
      const body = document.createElement("div");
      body.className = "card-body";
      const heading = document.createElement("h3");
      heading.className = "h5";
      heading.textContent = role.name;
      const description = document.createElement("p");
      description.className = "text-secondary small";
      description.textContent = role.description || "Rol del sistema.";
      const permissions = document.createElement("p");
      permissions.className = "small mb-3";
      permissions.textContent = role.permissions.map((item) => item.code).join(", ") || "Sin permisos asignados";
      body.append(heading, description, permissions);
      if (canManage) {
        const edit = document.createElement("button");
        edit.type = "button";
        edit.className = "btn btn-outline-primary btn-sm";
        edit.textContent = "Configurar permisos";
        edit.addEventListener("click", () => openPermissions(role));
        body.append(edit);
      }
      card.append(body);
      article.append(card);
      elements.list.append(article);
    }
  }

  function openPermissions(role) {
    if (!canManage || !catalog) return;
    elements.permissionsRoleId.value = role.id;
    elements.permissionsTitle.textContent = `Permisos de ${role.name}`;
    elements.permissionsList.replaceChildren();
    const selected = new Set(role.permissions.map((item) => item.id));
    for (const permission of catalog.availablePermissions) {
      const wrapper = document.createElement("div");
      wrapper.className = "form-check mb-2";
      const input = document.createElement("input");
      input.className = "form-check-input";
      input.type = "checkbox";
      input.value = permission.id;
      input.id = `permission-${permission.id}`;
      input.checked = selected.has(permission.id);
      const label = document.createElement("label");
      label.className = "form-check-label";
      label.htmlFor = input.id;
      label.textContent = `${permission.code} — ${permission.description}`;
      wrapper.append(input, label);
      elements.permissionsList.append(wrapper);
    }
    open(elements.permissionsDialog);
  }

  async function savePermissions() {
    const roleId = elements.permissionsRoleId.value;
    const permissionIds = Array.from(elements.permissionsList.querySelectorAll("input:checked"), (input) => input.value);
    if (!window.confirm("¿Confirmas actualizar los permisos de este rol?")) return;
    setBusy(elements.savePermissions, true, "Guardando…");
    try {
      await client.requestJson(`/api/v1/roles/${roleId}/permissions`, {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ permissionIds })
      });
      close(elements.permissionsDialog);
      notice("Permisos actualizados correctamente.");
      await loadCatalog();
    } catch (error) {
      dialogError(elements.permissionsError, error);
    } finally {
      setBusy(elements.savePermissions, false, "Guardar permisos");
    }
  }

  async function openAssignment() {
    if (!canManage || !catalog) return;
    try {
      const page = await client.requestJson("/api/v1/users?page=0&size=100&sortBy=email&direction=asc");
      userPage = validUsers(page);
      elements.assignmentUser.replaceChildren();
      for (const user of userPage.content) {
        const option = document.createElement("option");
        option.value = user.id;
        option.textContent = `${user.firstName} ${user.lastName} (${user.email})`;
        option.dataset.version = String(user.version);
        elements.assignmentUser.append(option);
      }
      elements.assignmentRoles.replaceChildren();
      for (const role of catalog.roles) {
        const wrapper = document.createElement("div"); wrapper.className = "form-check mb-2";
        const input = document.createElement("input"); input.className = "form-check-input"; input.type = "checkbox"; input.value = role.id; input.id = `assignment-${role.id}`;
        const label = document.createElement("label"); label.className = "form-check-label"; label.htmlFor = input.id; label.textContent = role.name;
        wrapper.append(input, label); elements.assignmentRoles.append(wrapper);
      }
      elements.assignmentUser.dispatchEvent(new Event("change"));
      open(elements.assignmentDialog);
    } catch (error) { showError(error); }
  }

  function synchronizeAssignmentRoles() {
    const selected = userPage?.content.find((user) => user.id === elements.assignmentUser.value);
    const selectedRoleId = selected?.role?.id ?? null;
    for (const input of elements.assignmentRoles.querySelectorAll("input[type=checkbox]")) {
      input.checked = input.value === selectedRoleId;
    }
  }

  async function saveAssignment() {
    const option = elements.assignmentUser.selectedOptions[0];
    if (!option) return;
    const roleIds = Array.from(elements.assignmentRoles.querySelectorAll("input:checked"), (input) => input.value);
    if (!window.confirm("¿Confirmas actualizar los roles de este usuario?")) return;
    setBusy(elements.saveAssignment, true, "Guardando…");
    try {
      await client.requestJson(`/api/v1/users/${option.value}/roles`, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ roleIds, version: Number(option.dataset.version) })
      });
      close(elements.assignmentDialog); notice("Roles del usuario actualizados correctamente.");
    } catch (error) { dialogError(elements.assignmentError, error); }
    finally { setBusy(elements.saveAssignment, false, "Guardar roles"); }
  }

  function state(value) {
    elements.loading.hidden = value !== "loading"; elements.empty.hidden = value !== "empty";
    elements.content.hidden = value !== "content"; elements.error.hidden = value !== "error";
  }
  function showError(error) { state("error"); elements.errorMessage.textContent = message(error); }
  function notice(value) { elements.notice.textContent = value; elements.notice.hidden = false; elements.notice.focus(); }
  function dialogError(element, error) { element.textContent = message(error); element.hidden = false; }
}

function required(document) {
  const selectors = { open: "#roles-nav-button", home: "#dashboard-home", rolesView: "#roles-view", back: "#roles-back-button", title: "#roles-title", loading: "#roles-loading", empty: "#roles-empty", content: "#roles-content", error: "#roles-error", errorMessage: "#roles-error-message", retry: "#retry-roles", list: "#roles-list", notice: "#roles-notice", assign: "#assign-user-roles-button", permissionsDialog: "#role-permissions-dialog", permissionsTitle: "#role-permissions-title", permissionsRoleId: "#role-permissions-id", permissionsList: "#role-permissions-list", permissionsError: "#role-permissions-error", cancelPermissions: "#cancel-role-permissions", savePermissions: "#save-role-permissions", assignmentDialog: "#user-roles-dialog", assignmentUser: "#user-roles-user", assignmentRoles: "#user-roles-list", assignmentError: "#user-roles-error", cancelAssignment: "#cancel-user-roles", saveAssignment: "#save-user-roles" };
  const entries = Object.fromEntries(Object.entries(selectors).map(([key, selector]) => [key, document.querySelector(selector)]));
  for (const [name, value] of Object.entries(entries)) if (!value) throw new Error(`Falta ${name}`);
  return entries;
}
function validCatalog(value) { if (!value || !Array.isArray(value.roles) || !Array.isArray(value.availablePermissions)) throw new ApiRequestError({ kind: "INVALID_RESPONSE", code: "INVALID_RESPONSE", message: "Respuesta de roles inválida." }); return value; }
function validUsers(value) { if (!value || !Array.isArray(value.content)) throw new ApiRequestError({ kind: "INVALID_RESPONSE", code: "INVALID_RESPONSE", message: "Respuesta de usuarios inválida." }); return value; }
function message(error) { return error instanceof ApiRequestError ? error.message : "No fue posible completar la operación."; }
function open(dialog) { if (!dialog.open) dialog.showModal(); }
function close(dialog) { if (dialog.open) dialog.close(); }
function setBusy(button, busy, text) { button.disabled = busy; button.textContent = text; }
