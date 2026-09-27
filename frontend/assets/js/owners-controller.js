import { ApiRequestError } from "./api-client.js";

const OWNER_READ = "OWNER_READ";
const OWNER_CREATE = "OWNER_CREATE";
const OWNER_UPDATE = "OWNER_UPDATE";

export function createOwnersController({ document, client }) {
  const e = elements(document);
  let permitted = false;
  let canCreate = false;
  let canUpdate = false;
  let page = 0;
  let totalPages = 0;
  let loading = false;
  let submitting = false;
  let editingOwnerId = null;
  let editTrigger = null;
  const ownersById = new Map();

  e.open.addEventListener("click", open);
  e.back.addEventListener("click", home);
  e.form.addEventListener("submit", submit);
  e.retry.addEventListener("click", () => search(page));
  e.previous.addEventListener("click", () => search(page - 1));
  e.next.addEventListener("click", () => search(page + 1));
  e.newOwner.addEventListener("click", openCreateEditor);
  e.cancelOwner.addEventListener("click", () => closeEditor({ restoreFocus: true }));
  e.editor.addEventListener("submit", submitEditor);
  e.rows.addEventListener("click", handleEditAction);
  e.cards.addEventListener("click", handleEditAction);

  return Object.freeze({
    sessionReady,
    sessionEnded,
    openEditOwnerById: (ownerId) => openEditOwner(ownerId, null)
  });

  function sessionReady(identity) {
    const authorities = new Set(identity?.authorities ?? []);
    permitted = authorities.has(OWNER_READ);
    canCreate = authorities.has(OWNER_CREATE);
    canUpdate = authorities.has(OWNER_UPDATE);
    e.open.hidden = !permitted;
    e.newOwner.hidden = !canCreate;
    home();
  }

  function sessionEnded() {
    permitted = false;
    canCreate = false;
    canUpdate = false;
    editingOwnerId = null;
    editTrigger = null;
    ownersById.clear();
    e.open.hidden = true;
    e.newOwner.hidden = true;
    e.view.hidden = true;
    e.home.hidden = false;
    if (e.notice) e.notice.hidden = true;
    e.rows.replaceChildren();
    e.cards.replaceChildren();
    closeEditor({ restoreFocus: false });
  }

  function open() {
    if (!permitted) return;
    e.home.hidden = true;
    e.view.hidden = false;
    e.title.focus({ preventScroll: true });
    e.search.focus({ preventScroll: true });
  }

  function home() {
    e.view.hidden = true;
    e.home.hidden = false;
    if (e.notice) e.notice.hidden = true;
  }

  function submit(event) {
    event.preventDefault();
    if (e.notice) e.notice.hidden = true;
    if (!e.form.checkValidity()) {
      e.form.classList.add("was-validated");
      e.search.focus();
      return;
    }
    totalPages = 0;
    search(0);
  }

  async function search(requestedPage, { keepNotice = false } = {}) {
    if (loading || requestedPage < 0 || (totalPages > 0 && requestedPage >= totalPages)) return;
    if (!keepNotice && e.notice) e.notice.hidden = true;
    loading = true;
    state("loading");
    try {
      const q = new URLSearchParams({
        query: e.search.value.trim(),
        page: String(requestedPage),
        size: "10",
        sortBy: e.sort.value,
        direction: e.direction.value
      });
      const result = valid(await client.requestJson(`/api/v1/owners?${q}`));
      page = result.page;
      totalPages = result.totalPages;
      render(result.content);
      pagination(result);
      state(result.content.length ? "content" : "empty");
    } catch (error) {
      e.errorMessage.textContent = error instanceof ApiRequestError
        ? error.message
        : "No fue posible buscar propietarios.";
      state("error");
    } finally {
      loading = false;
    }
  }

  function render(owners) {
    e.rows.replaceChildren();
    e.cards.replaceChildren();
    ownersById.clear();

    for (const owner of owners) {
      const ownerId = String(owner.id ?? "");
      ownersById.set(ownerId, owner);
      const name = `${owner.firstName ?? ""} ${owner.lastName ?? ""}`.trim();
      const documentText = [owner.documentType, owner.documentNumber].filter(Boolean).join(" ") || "Sin documento";

      const row = document.createElement("tr");
      row.dataset.ownerId = ownerId;
      for (const text of [name, documentText, owner.phone ?? ""]) {
        const cell = document.createElement("td");
        cell.textContent = text;
        row.append(cell);
      }
      const actionCell = document.createElement("td");
      actionCell.className = "text-end";
      actionCell.append(createOwnerActionElement(ownerId));
      row.append(actionCell);
      e.rows.append(row);

      const cardCol = document.createElement("div");
      cardCol.className = "col-12";
      const card = document.createElement("article");
      card.className = "card";
      card.dataset.ownerId = ownerId;
      const body = document.createElement("div");
      body.className = "card-body";
      for (const [tag, text, css] of [
        ["h3", name, "h6 mb-2"],
        ["p", documentText, "small mb-1"],
        ["p", owner.phone ?? "", "small text-secondary mb-2"]
      ]) {
        const node = document.createElement(tag);
        node.className = css;
        node.textContent = text;
        body.append(node);
      }
      body.append(createOwnerActionElement(ownerId));
      card.append(body);
      cardCol.append(card);
      e.cards.append(cardCol);
    }
  }

  function createOwnerActionElement(ownerId) {
    if (canUpdate && ownerId) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "btn btn-sm btn-outline-primary";
      button.dataset.action = "edit-owner";
      button.dataset.ownerId = ownerId;
      button.textContent = "Editar propietario";
      return button;
    }
    const label = document.createElement("span");
    label.className = "small text-secondary";
    label.textContent = "Solo lectura";
    return label;
  }

  function handleEditAction(event) {
    const button = event.target?.closest?.("[data-action='edit-owner']");
    if (!button) return;
    const ownerId = button.dataset.ownerId;
    if (ownerId) {
      openEditOwner(ownerId, button);
    }
  }

  function pagination(result) {
    e.previous.disabled = result.page <= 0;
    e.next.disabled = result.page + 1 >= result.totalPages;
    e.pageStatus.textContent = result.totalPages
      ? `Página ${result.page + 1} de ${result.totalPages} · ${result.totalElements} propietarios`
      : "Sin resultados";
  }

  function state(value) {
    e.loading.hidden = value !== "loading";
    e.empty.hidden = value !== "empty";
    e.content.hidden = value !== "content";
    e.error.hidden = value !== "error";
  }

  function resetEditorValidation() {
    e.editor.classList.remove("was-validated");
    e.formError.hidden = true;
    e.validation.hidden = true;
    for (const input of e.editor.querySelectorAll("input")) {
      input.removeAttribute("aria-invalid");
    }
  }

  function openCreateEditor() {
    if (!canCreate) return;
    editingOwnerId = null;
    editTrigger = e.newOwner;
    e.editor.reset();
    resetEditorValidation();
    if (e.dialogTitle) e.dialogTitle.textContent = "Nuevo propietario";
    e.save.textContent = "Registrar propietario";
    e.dialog.showModal();
    e.firstName.focus();
  }

  function openEditOwner(ownerId, trigger) {
    if (!canUpdate) return;
    const owner = ownersById.get(String(ownerId));
    if (!owner) return;
    editingOwnerId = String(owner.id);
    editTrigger = trigger ?? null;
    e.editor.reset();
    resetEditorValidation();
    if (e.dialogTitle) e.dialogTitle.textContent = "Editar propietario";
    e.save.textContent = "Guardar cambios";
    e.firstName.value = owner.firstName ?? "";
    e.lastName.value = owner.lastName ?? "";
    e.documentType.value = owner.documentType ?? "";
    e.documentNumber.value = owner.documentNumber ?? "";
    e.phone.value = owner.phone ?? "";
    e.email.value = owner.email ?? "";
    e.address.value = owner.address ?? "";
    e.notifications.checked = Boolean(owner.notificationsAccepted);
    e.dialog.showModal();
    e.firstName.focus();
  }

  function closeEditor({ restoreFocus = false } = {}) {
    if (e.dialog.open) e.dialog.close();
    e.phone.value = "";
    const target = editTrigger;
    editingOwnerId = null;
    editTrigger = null;
    if (restoreFocus && target && typeof target.focus === "function") {
      target.focus({ preventScroll: true });
    }
  }

  async function submitEditor(event) {
    event.preventDefault();
    const isEditing = editingOwnerId !== null;
    if (submitting || (isEditing ? !canUpdate : !canCreate)) return;
    e.editor.classList.add("was-validated");
    if (!editorIsValid(isEditing)) return;
    const payload = {
      firstName: e.firstName.value,
      lastName: e.lastName.value,
      documentType: e.documentType.value,
      documentNumber: e.documentNumber.value,
      phone: e.phone.value,
      email: e.email.value,
      address: e.address.value,
      notificationsAccepted: e.notifications.checked,
      confirmPhoneDuplicate: false
    };
    await sendOwnerRequest(payload, isEditing ? editingOwnerId : null);
  }

  function editorIsValid(isEditing = false) {
    const invalid = [...e.editor.querySelectorAll("input")].filter((input) => !input.checkValidity());
    for (const input of e.editor.querySelectorAll("input")) {
      if (invalid.includes(input)) input.setAttribute("aria-invalid", "true");
      else input.removeAttribute("aria-invalid");
    }
    if (!invalid.length) {
      e.validation.hidden = true;
      return true;
    }
    const verb = isEditing ? "guardar los cambios del propietario" : "registrar el propietario";
    e.validation.textContent = `Corrige ${invalid.length === 1 ? "el campo indicado" : "los campos indicados"} antes de ${verb}.`;
    e.validation.hidden = false;
    invalid[0].focus();
    return false;
  }

  async function sendOwnerRequest(payload, targetOwnerId) {
    const isEditing = targetOwnerId !== null;
    submitting = true;
    e.save.disabled = true;
    e.formError.hidden = true;
    try {
      const url = isEditing ? `/api/v1/owners/${encodeURIComponent(targetOwnerId)}` : "/api/v1/owners";
      const method = isEditing ? "PUT" : "POST";
      const saved = await client.requestJson(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      closeEditor({ restoreFocus: false });
      if (isEditing) {
        if (e.notice) {
          e.notice.textContent = "Datos del propietario actualizados correctamente.";
          e.notice.hidden = false;
          e.notice.focus({ preventScroll: true });
        }
        if (e.search.value.trim().length >= 2) {
          await search(page, { keepNotice: true });
        } else {
          e.search.value = `${saved.firstName ?? ""} ${saved.lastName ?? ""}`.trim();
          totalPages = 0;
          await search(0, { keepNotice: true });
        }
      } else {
        e.search.value = `${saved.firstName ?? ""} ${saved.lastName ?? ""}`.trim();
        totalPages = 0;
        await search(0);
      }
    } catch (error) {
      const confirmQuestion = isEditing
        ? "Ya existe un teléfono igual en la clínica. ¿Confirmas la actualización?"
        : "Ya existe un teléfono igual. ¿Confirmas el registro?";
      if (
        error instanceof ApiRequestError
        && error.status === 409
        && error.message.includes("teléfono")
        && window.confirm(confirmQuestion)
      ) {
        payload.confirmPhoneDuplicate = true;
        submitting = false;
        e.save.disabled = false;
        return sendOwnerRequest(payload, targetOwnerId);
      }
      e.formError.textContent = error instanceof ApiRequestError
        ? error.message
        : (isEditing ? "No fue posible actualizar el propietario." : "No fue posible registrar el propietario.");
      e.formError.hidden = false;
    } finally {
      submitting = false;
      e.save.disabled = false;
    }
  }
}

function elements(document) {
  const selectors = {
    open: "#owners-nav-button",
    home: "#dashboard-home",
    view: "#owners-view",
    back: "#owners-back-button",
    title: "#owners-title",
    form: "#owner-search-form",
    search: "#owner-search",
    sort: "#owner-sort",
    direction: "#owner-direction",
    loading: "#owners-loading",
    empty: "#owners-empty",
    content: "#owners-content",
    error: "#owners-error",
    errorMessage: "#owners-error-message",
    retry: "#retry-owners",
    rows: "#owners-table-body",
    cards: "#owners-cards",
    previous: "#owners-previous-page",
    next: "#owners-next-page",
    pageStatus: "#owners-page-status",
    newOwner: "#new-owner-button",
    dialog: "#owner-editor-dialog",
    editor: "#owner-editor-form",
    formError: "#owner-form-error",
    validation: "#owner-form-validation",
    firstName: "#owner-first-name",
    lastName: "#owner-last-name",
    documentType: "#owner-document-type",
    documentNumber: "#owner-document-number",
    phone: "#owner-phone",
    email: "#owner-email",
    address: "#owner-address",
    notifications: "#owner-notifications",
    save: "#save-owner-button",
    cancelOwner: "#cancel-owner-button"
  };
  const result = Object.fromEntries(
    Object.entries(selectors).map(([key, value]) => [key, document.querySelector(value)])
  );
  for (const [key, value] of Object.entries(result)) {
    if (!value) throw new Error(`Falta ${key}`);
  }
  result.notice = document.querySelector("#owner-notice");
  result.dialogTitle = document.querySelector("#owner-editor-title");
  return result;
}

function valid(value) {
  if (
    !value
    || !Array.isArray(value.content)
    || !Number.isInteger(value.page)
    || !Number.isInteger(value.totalPages)
  ) {
    throw new ApiRequestError({
      kind: "INVALID_RESPONSE",
      code: "INVALID_RESPONSE",
      message: "Respuesta de propietarios inválida."
    });
  }
  return value;
}
