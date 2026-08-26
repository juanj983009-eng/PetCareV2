import { ApiRequestError } from "./api-client.js";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function createAuthController({ document, window, client, errors }) {
  const elements = findElements(document);
  let authenticatedIdentity = null;
  let status = "INITIALIZING";
  let submitting = false;
  let validationPromise = null;
  let sessionEpoch = 0;

  const removeSessionRejectedHandler = client.setSessionRejectedHandler(handleSessionRejected);
  elements.form.addEventListener("submit", handleLoginSubmit);
  elements.logoutButton.addEventListener("click", logout);
  elements.retryButton.addEventListener("click", handleRetry);
  window.addEventListener("pageshow", synchronizeAfterNavigation);
  window.addEventListener("popstate", synchronizeAfterNavigation);

  return Object.freeze({
    start,
    logout,
    restoreSession,
    snapshot: () => Object.freeze({ status, identity: authenticatedIdentity }),
    destroy
  });

  async function start() {
    if (client.hasAccessToken()) {
      await restoreSession();
      return;
    }
    showLogin();
  }

  async function handleLoginSubmit(event) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    errors.clearApiErrors(elements.form, elements.loginErrors);
    elements.form.classList.add("was-validated");
    if (!elements.form.checkValidity()) {
      elements.form.querySelector(":invalid")?.focus();
      return;
    }

    submitting = true;
    setLoginLoading(true);
    const fields = new FormData(elements.form);
    try {
      const response = await client.requestJson("/api/v1/auth/login", {
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
      client.setAccessToken(login.accessToken);
      sessionEpoch += 1;
      await restoreSession(login.identity.authorities);
    } catch (error) {
      if (!client.hasAccessToken()) {
        showLoginError(normalizeError(error));
      } else if (validationPromise === null && status !== "SESSION_EXPIRED") {
        showValidationFailure(normalizeError(error));
      }
    } finally {
      elements.password.value = "";
      submitting = false;
      setLoginLoading(false);
    }
  }

  function restoreSession(authorities = []) {
    if (!client.hasAccessToken()) {
      showLogin();
      return Promise.resolve();
    }
    if (validationPromise !== null) {
      return validationPromise;
    }

    const trustedAuthorities = Array.isArray(authorities) ? authorities : [];
    const validationEpoch = sessionEpoch;
    showSessionValidation();
    const pending = validateSession(trustedAuthorities, validationEpoch);
    validationPromise = pending;
    return pending.finally(() => {
      if (validationPromise === pending) {
        validationPromise = null;
      }
    });
  }

  async function validateSession(authorities, validationEpoch) {
    try {
      const session = validSessionResponse(await client.requestJson("/api/v1/auth/session"));
      if (validationEpoch !== sessionEpoch || !client.hasAccessToken()) {
        return;
      }
      authenticatedIdentity = Object.freeze({
        ...session,
        authorities: Object.freeze([...authorities])
      });
      showDashboard();
    } catch (error) {
      if (validationEpoch === sessionEpoch && client.hasAccessToken()) {
        showValidationFailure(normalizeError(error));
      }
    }
  }

  function logout() {
    sessionEpoch += 1;
    validationPromise = null;
    client.clearAccessToken();
    authenticatedIdentity = null;
    elements.password.value = "";
    errors.clearApiErrors(elements.form, elements.loginErrors);
    elements.form.classList.remove("was-validated");
    showLogin();
  }

  function handleSessionRejected(error) {
    sessionEpoch += 1;
    validationPromise = null;
    authenticatedIdentity = null;
    elements.password.value = "";
    showLogin(sessionExpiredError(error));
  }

  function synchronizeAfterNavigation(event) {
    if (!client.hasAccessToken()) {
      authenticatedIdentity = null;
      elements.password.value = "";
      showLogin();
      return;
    }
    if (event.type === "pageshow" && event.persisted) {
      restoreSession();
    }
  }

  function showLogin(error = null) {
    status = error === null ? "ANONYMOUS" : "SESSION_EXPIRED";
    showOnly(elements.loginView);
    errors.clearApiErrors(elements.form, elements.loginErrors);
    if (error === null) {
      elements.clinicSlug.focus();
    } else {
      showLoginError(error);
    }
  }

  function showLoginError(error) {
    const summary = errors.applyApiError(elements.form, error, elements.loginErrors);
    summary.tabIndex = -1;
    summary.focus();
  }

  function showSessionValidation() {
    status = "VALIDATING";
    showOnly(elements.validationView);
    elements.validationTitle.focus({ preventScroll: true });
  }

  function showValidationFailure(error) {
    status = error.status === 403 ? "ACCESS_DENIED" : "VALIDATION_FAILED";
    showOnly(elements.validationErrorView);
    elements.validationErrorMessage.textContent = validationFailureMessage(error);
    elements.validationCorrelation.textContent = error.correlationId === null
      ? ""
      : `Código de seguimiento: ${error.correlationId}`;
    elements.validationCorrelation.hidden = error.correlationId === null;
    elements.retryButton.focus({ preventScroll: true });
  }

  function showDashboard() {
    status = "AUTHENTICATED";
    showOnly(elements.dashboardView);
    elements.authorityList.replaceChildren();
    const authorities = authenticatedIdentity?.authorities ?? [];
    if (authorities.length === 0) {
      const message = document.createElement("span");
      message.className = "text-secondary";
      message.textContent = "Sesión verificada.";
      elements.authorityList.append(message);
    } else {
      for (const authority of authorities) {
        const badge = document.createElement("span");
        badge.className = "badge text-bg-light border";
        badge.textContent = authority;
        elements.authorityList.append(badge);
      }
    }
    elements.dashboardTitle.focus({ preventScroll: true });
  }

  function showOnly(view) {
    for (const candidate of [
      elements.loginView,
      elements.validationView,
      elements.validationErrorView,
      elements.dashboardView
    ]) {
      candidate.hidden = candidate !== view;
    }
  }

  function setLoginLoading(loading) {
    elements.submitButton.disabled = loading;
    elements.submitButton.setAttribute("aria-busy", String(loading));
    elements.submitLabel.textContent = loading ? "Verificando…" : "Iniciar sesión";
    elements.spinner.hidden = !loading;
  }

  function destroy() {
    removeSessionRejectedHandler();
    elements.form.removeEventListener("submit", handleLoginSubmit);
    elements.logoutButton.removeEventListener("click", logout);
    elements.retryButton.removeEventListener("click", handleRetry);
    window.removeEventListener("pageshow", synchronizeAfterNavigation);
    window.removeEventListener("popstate", synchronizeAfterNavigation);
  }

  function handleRetry() {
    restoreSession();
  }
}

function findElements(document) {
  const elements = {
    form: document.querySelector("#login-form"),
    loginErrors: document.querySelector("#login-errors"),
    loginView: document.querySelector("#login-view"),
    validationView: document.querySelector("#session-validation-view"),
    validationErrorView: document.querySelector("#session-validation-error-view"),
    dashboardView: document.querySelector("#dashboard-view"),
    submitButton: document.querySelector("#login-button"),
    submitLabel: document.querySelector("#login-button-label"),
    spinner: document.querySelector("#login-spinner"),
    clinicSlug: document.querySelector("#clinicSlug"),
    password: document.querySelector("#password"),
    authorityList: document.querySelector("#authority-list"),
    logoutButton: document.querySelector("#logout-button"),
    retryButton: document.querySelector("#retry-session-button"),
    validationTitle: document.querySelector("#session-validation-title"),
    validationErrorMessage: document.querySelector("#session-validation-error-message"),
    validationCorrelation: document.querySelector("#session-validation-correlation"),
    dashboardTitle: document.querySelector("#dashboard-title")
  };
  for (const [name, element] of Object.entries(elements)) {
    if (element === null) {
      throw new Error(`No se encontró el elemento requerido: ${name}`);
    }
  }
  return elements;
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
    throw invalidResponse();
  }
  return value;
}

function validSessionResponse(value) {
  if (
    !value
    || !UUID_PATTERN.test(value.userId)
    || !UUID_PATTERN.test(value.tenantId)
    || (value.branchId !== null && !UUID_PATTERN.test(value.branchId))
  ) {
    throw invalidResponse();
  }
  return Object.freeze({
    userId: value.userId,
    tenantId: value.tenantId,
    branchId: value.branchId
  });
}

function invalidResponse() {
  return new ApiRequestError({
    kind: "INVALID_RESPONSE",
    code: "INVALID_RESPONSE",
    message: "El servidor devolvió una respuesta no válida."
  });
}

function normalizeError(error) {
  if (error instanceof ApiRequestError) {
    return error;
  }
  return new ApiRequestError({
    kind: "INVALID_RESPONSE",
    code: "INVALID_RESPONSE",
    message: "No fue posible completar la operación."
  });
}

function sessionExpiredError(error) {
  return new ApiRequestError({
    kind: "SESSION_EXPIRED",
    status: 401,
    code: "SESSION_EXPIRED",
    message: "Tu sesión expiró o dejó de ser válida. Inicia sesión nuevamente.",
    correlationId: error.correlationId
  });
}

function validationFailureMessage(error) {
  if (error.kind === "NETWORK") {
    return "No fue posible validar tu sesión. Revisa la conexión e inténtalo nuevamente.";
  }
  if (error.status === 403) {
    return "El servidor denegó la validación. Tu sesión local se conserva para volver a intentarlo.";
  }
  return "El servidor devolvió una respuesta no válida al verificar tu sesión. Inténtalo nuevamente.";
}
