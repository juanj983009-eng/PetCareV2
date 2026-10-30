import { ApiRequestError } from "./api-client.js";

const ROLE_READ = "ROLE_READ";
const ROLE_MANAGE = "ROLE_MANAGE";

export function createRolesController({ document, client }) {
  const elements = required(document);
  const overview = {
    summary: document.querySelector("#roles-summary"),
    roles: document.querySelector("#roles-total"),
    permissions: document.querySelector("#roles-permissions-total")
  };
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
    if (overview.summary) overview.summary.hidden = true;
    if (overview.roles) overview.roles.textContent = "";
    if (overview.permissions) overview.permissions.textContent = "";
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
    if (overview.roles) overview.roles.textContent = String(roles.length);
    if (overview.permissions) overview.permissions.textContent = String(roles.reduce((total, role) => total + role.permissions.length, 0));
    for (const role of roles) {
      const article = document.createElement("article");
      article.className = "col-12 col-md-6";
      const roleKey = role.name.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toUpperCase();
      article.dataset.roleTone = ["ALMACEN", "VETERINARIO"].includes(roleKey) ? "gold" : "sage";
      const card = document.createElement("div");
      card.className = "card h-100 pc-record-card";
      const body = document.createElement("div");
      body.className = "card-body";
      const header = document.createElement("div");
      header.className = "pc-role-heading";
      const mark = document.createElement("span");
      mark.className = "pc-role-monogram";
      mark.setAttribute("aria-hidden", "true");
      mark.textContent = Array.from(role.name).slice(0, 2).join("");
      const heading = document.createElement("h3");
      heading.className = "h5";
      heading.textContent = role.name;
      const description = document.createElement("p");
      description.className = "text-secondary small";
      description.textContent = role.description || "Rol del sistema.";
      const identity = document.createElement("div");
      identity.className = "pc-role-identity";
      identity.append(heading, description);
      const emblem = document.createElement("span");
      emblem.className = "pc-role-emblem";
      emblem.append(roleIcon(document, roleKey));
      header.append(mark, identity, emblem);
      const permissions = document.createElement("details");
      permissions.className = "pc-role-permissions";
      const summary = document.createElement("summary");
      summary.append(roleIcon(document, "SHIELD"), document.createTextNode(`${role.permissions.length} ${role.permissions.length === 1 ? "permiso asignado" : "permisos asignados"}`));
      permissions.append(summary);
      const list = document.createElement("ul");
      for (const permission of role.permissions) {
        const item = document.createElement("li");
        const code = document.createElement("code");
        code.textContent = permission.code;
        item.append(code);
        if (permission.description) {
          const caption = document.createElement("span");
          caption.textContent = permission.description;
          item.append(caption);
        }
        list.append(item);
      }
      if (!role.permissions.length) {
        const item = document.createElement("li");
        item.textContent = "Este rol no tiene permisos asignados.";
        list.append(item);
      }
      permissions.append(list);
      body.append(header, permissions);
      if (canManage) {
        const edit = document.createElement("button");
        edit.type = "button";
        edit.className = "btn btn-outline-primary btn-sm";
        edit.append(roleIcon(document, "ADMINISTRADOR"), document.createTextNode("Configurar permisos"));
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
    if (overview.summary) overview.summary.hidden = value !== "content";
    elements.loading.hidden = value !== "loading"; elements.empty.hidden = value !== "empty";
    elements.content.hidden = value !== "content"; elements.error.hidden = value !== "error";
  }
  function showError(error) { state("error"); elements.errorMessage.textContent = message(error); }
  function notice(value) { elements.notice.textContent = value; elements.notice.hidden = false; elements.notice.focus(); }
  function dialogError(element, error) { element.textContent = message(error); element.hidden = false; }
}

function roleIcon(document, key) {
  const paths = {
    ADMINISTRADOR: ["M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z", "m9 3 .6-2h4.8l.6 2 2 .9 1.9-.5 2.4 4.2-1.4 1.5V12l1.4 1.5-2.4 4.2-1.9-.5-2 .9-.6 2H9.6L9 18l-2-.9-1.9.5-2.4-4.2L4.1 12V9.1L2.7 7.6l2.4-4.2L7 3.9Z"],
    ALMACEN: ["m12 3 9 5v9l-9 5-9-5V8Z", "m3 8 9 5 9-5M12 13v9M7 5.8l9 5"],
    RECEPCIONISTA: ["M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM2 22v-4a7 7 0 0 1 14 0v4M17 4a4 4 0 0 1 0 8m2 3a6 6 0 0 1 3 5v2"],
    VETERINARIO: ["M5 3v5a5 5 0 0 0 10 0V3M3 3h4m6 0h4M10 13v3a5 5 0 0 0 10 0v-3", "M20 9a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z"],
    SHIELD: ["m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z"]
  };
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  for (const [name, value] of Object.entries({ viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "1.7", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true", focusable: "false" })) svg.setAttribute(name, value);
  for (const d of paths[key] ?? paths.SHIELD) {
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
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
