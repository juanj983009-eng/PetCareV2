import {
  ApiRequestError,
  clearAccessToken,
  hasAccessToken,
  requestJson,
  setAccessToken
} from "./api-client.js";
import { applyApiError, clearApiErrors } from "./form-errors.js";

const form = document.querySelector("#login-form");
const errorContainer = document.querySelector("#login-errors");
const loginView = document.querySelector("#login-view");
const dashboardView = document.querySelector("#dashboard-view");
const submitButton = document.querySelector("#login-button");
const submitLabel = document.querySelector("#login-button-label");
const spinner = document.querySelector("#login-spinner");
const password = document.querySelector("#password");
const authorityList = document.querySelector("#authority-list");
let submitting = false;

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (submitting) {
    return;
  }
  clearApiErrors(form, errorContainer);
  form.classList.add("was-validated");
  if (!form.checkValidity()) {
    form.querySelector(":invalid")?.focus();
    return;
  }

  submitting = true;
  setLoading(true);
  const fields = new FormData(form);
  try {
    const response = await requestJson("/api/v1/auth/login", {
      method: "POST",
      useAccessToken: false,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        clinicSlug: fields.get("clinicSlug"),
        email: fields.get("email"),
        password: fields.get("password")
      })
    });
    const login = validLoginResponse(response);
    setAccessToken(login.accessToken);
    await requestJson("/api/v1/auth/session");
    showDashboard(login.identity.authorities);
  } catch (error) {
    clearAccessToken();
    showError(normalizeError(error));
  } finally {
    password.value = "";
    submitting = false;
    setLoading(false);
  }
});

if (hasAccessToken()) {
  restoreSession();
} else {
  document.querySelector("#clinicSlug").focus();
}

async function restoreSession() {
  try {
    await requestJson("/api/v1/auth/session");
    showDashboard([]);
  } catch {
    clearAccessToken();
    showLogin();
  }
}

function validLoginResponse(value) {
  const identity = value?.identity;
  if (
    !value
    || typeof value.accessToken !== "string"
    || value.accessToken.length === 0
    || value.tokenType !== "Bearer"
    || typeof value.expiresAt !== "string"
    || !identity
    || !Array.isArray(identity.authorities)
    || !identity.authorities.every((authority) => typeof authority === "string")
  ) {
    throw new ApiRequestError({
      kind: "INVALID_RESPONSE",
      code: "INVALID_RESPONSE",
      message: "El servidor devolvió una respuesta no válida."
    });
  }
  return value;
}

function normalizeError(error) {
  if (error instanceof ApiRequestError) {
    return error;
  }
  return new ApiRequestError({
    kind: "INVALID_RESPONSE",
    code: "INVALID_RESPONSE",
    message: "No fue posible completar el inicio de sesión."
  });
}

function showError(error) {
  const summary = applyApiError(form, error, errorContainer);
  summary.tabIndex = -1;
  summary.focus();
}

function setLoading(loading) {
  submitButton.disabled = loading;
  submitButton.setAttribute("aria-busy", String(loading));
  submitLabel.textContent = loading ? "Verificando…" : "Iniciar sesión";
  spinner.hidden = !loading;
}

function showDashboard(authorities) {
  loginView.hidden = true;
  dashboardView.hidden = false;
  authorityList.replaceChildren();
  if (authorities.length === 0) {
    const message = document.createElement("span");
    message.className = "text-secondary";
    message.textContent = "Sesión verificada.";
    authorityList.append(message);
  } else {
    for (const authority of authorities) {
      const badge = document.createElement("span");
      badge.className = "badge text-bg-light border";
      badge.textContent = authority;
      authorityList.append(badge);
    }
  }
  document.querySelector("#dashboard-title").focus({ preventScroll: true });
}

function showLogin() {
  dashboardView.hidden = true;
  loginView.hidden = false;
  document.querySelector("#clinicSlug").focus();
}
