import { ApiRequestError } from "./api-client.js";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function createAuthController({ document, window, client, errors, lifecycle = {} }) {
  const elements = findElements(document);
  const optional = findOptionalElements(document);
  let authenticatedIdentity = null;
  let status = "INITIALIZING";
  let submitting = false;
  let validationPromise = null;
  let sessionEpoch = 0;

  const removeSessionRejectedHandler = client.setSessionRejectedHandler(handleSessionRejected);
  elements.form.addEventListener("submit", handleLoginSubmit);
  elements.logoutButton.addEventListener("click", logout);
  elements.retryButton.addEventListener("click", handleRetry);
  const handleMobileNavDismiss = () => closeMobileNav(true);
  optional.togglePasswordButton?.addEventListener("click", togglePasswordVisibility);
  optional.mobileNavToggle?.addEventListener("click", toggleMobileNav);
  optional.mobileNavClose?.addEventListener("click", handleMobileNavDismiss);
  optional.mobileNavBackdrop?.addEventListener("click", handleMobileNavDismiss);
  optional.dashboardNavButton?.addEventListener("click", openDashboardHome);
  for (const [button, view] of [
    [optional.usersNavButton, optional.usersView],
    [optional.rolesNavButton, optional.rolesView],
    [optional.ownersNavButton, optional.ownersView]
  ]) {
    button?.addEventListener("click", () => activateModuleNav(button, view));
  }
  for (const backBtn of [optional.usersBackButton, optional.rolesBackButton, optional.ownersBackButton]) {
    backBtn?.addEventListener("click", () => markActiveNav(optional.dashboardNavButton));
  }
  document.addEventListener("keydown", handleKeydown);
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
      await restoreSession();
    } catch (error) {
      if (!client.hasAccessToken()) {
        showLoginError(normalizeError(error));
      } else if (validationPromise === null && status !== "SESSION_EXPIRED") {
        showValidationFailure(normalizeError(error));
      }
    } finally {
      resetPasswordInput();
      submitting = false;
      setLoginLoading(false);
    }
  }

  function restoreSession() {
    if (!client.hasAccessToken()) {
      showLogin();
      return Promise.resolve();
    }
    if (validationPromise !== null) {
      return validationPromise;
    }

    const validationEpoch = sessionEpoch;
    showSessionValidation();
    const pending = validateSession(validationEpoch);
    validationPromise = pending;
    return pending.finally(() => {
      if (validationPromise === pending) {
        validationPromise = null;
      }
    });
  }

  async function validateSession(validationEpoch) {
    try {
      const session = validSessionResponse(await client.requestJson("/api/v1/auth/session"));
      if (validationEpoch !== sessionEpoch || !client.hasAccessToken()) {
        return;
      }
      authenticatedIdentity = session;
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
    resetPasswordInput();
    closeMobileNav();
    errors.clearApiErrors(elements.form, elements.loginErrors);
    elements.form.classList.remove("was-validated");
    showLogin();
    lifecycle.ended?.();
  }

  function handleSessionRejected(error) {
    sessionEpoch += 1;
    validationPromise = null;
    authenticatedIdentity = null;
    resetPasswordInput();
    closeMobileNav();
    showLogin(sessionExpiredError(error));
    lifecycle.ended?.();
  }

  function synchronizeAfterNavigation(event) {
    if (!client.hasAccessToken()) {
      if (authenticatedIdentity === null && status === "ANONYMOUS" && !elements.loginView.hidden) {
        return;
      }
      authenticatedIdentity = null;
      resetPasswordInput();
      closeMobileNav();
      showLogin();
      lifecycle.ended?.();
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
    closeMobileNav();
    markActiveNav(optional.dashboardNavButton);
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
    lifecycle.authenticated?.(authenticatedIdentity);
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

  function togglePasswordVisibility() {
    const visible = elements.password.type === "password";
    elements.password.type = visible ? "text" : "password";
    if (optional.togglePasswordButton) {
      optional.togglePasswordButton.setAttribute("aria-pressed", String(visible));
      optional.togglePasswordButton.setAttribute("aria-label", visible ? "Ocultar contraseña" : "Mostrar contraseña");
      optional.togglePasswordButton.textContent = visible ? "Ocultar" : "Mostrar";
    }
  }

  function resetPasswordInput() {
    elements.password.value = "";
    elements.password.type = "password";
    if (optional.togglePasswordButton) {
      optional.togglePasswordButton.setAttribute("aria-pressed", "false");
      optional.togglePasswordButton.setAttribute("aria-label", "Mostrar contraseña");
      optional.togglePasswordButton.textContent = "Mostrar";
    }
  }

  function toggleMobileNav() {
    if (!optional.mobileNav) return;
    const opening = !optional.mobileNav.classList.contains("is-open");
    optional.mobileNav.classList.toggle("is-open", opening);
    optional.mobileNavToggle?.setAttribute("aria-expanded", String(opening));
    if (optional.mobileNavBackdrop) {
      optional.mobileNavBackdrop.hidden = !opening;
    }
    if (opening) {
      (optional.mobileNavClose ?? optional.dashboardNavButton)?.focus({ preventScroll: true });
    } else {
      optional.mobileNavToggle?.focus({ preventScroll: true });
    }
  }

  function closeMobileNav(restoreToggleFocus = false) {
    const wasOpen = Boolean(optional.mobileNav?.classList.contains("is-open"));
    optional.mobileNav?.classList.remove("is-open");
    optional.mobileNavToggle?.setAttribute("aria-expanded", "false");
    if (optional.mobileNavBackdrop) {
      optional.mobileNavBackdrop.hidden = true;
    }
    if (wasOpen && restoreToggleFocus) {
      optional.mobileNavToggle?.focus({ preventScroll: true });
    }
  }

  function openDashboardHome() {
    if (optional.usersView) optional.usersView.hidden = true;
    if (optional.rolesView) optional.rolesView.hidden = true;
    if (optional.ownersView) optional.ownersView.hidden = true;
    if (optional.dashboardHome) optional.dashboardHome.hidden = false;
    markActiveNav(optional.dashboardNavButton);
    closeMobileNav();
  }

  function activateModuleNav(button, targetView) {
    for (const view of [optional.usersView, optional.rolesView, optional.ownersView]) {
      if (view && view !== targetView) {
        view.hidden = true;
      }
    }
    markActiveNav(button);
    closeMobileNav();
  }

  function markActiveNav(activeButton) {
    for (const button of [
      optional.dashboardNavButton,
      optional.usersNavButton,
      optional.rolesNavButton,
      optional.ownersNavButton
    ]) {
      if (!button) continue;
      if (button === activeButton) {
        button.setAttribute("aria-current", "page");
      } else {
        button.removeAttribute("aria-current");
      }
    }
  }

  function handleKeydown(event) {
    if (event.key === "Escape" && optional.mobileNav?.classList.contains("is-open")) {
      closeMobileNav();
      optional.mobileNavToggle?.focus();
    }
  }

  function destroy() {
    removeSessionRejectedHandler();
    elements.form.removeEventListener("submit", handleLoginSubmit);
    elements.logoutButton.removeEventListener("click", logout);
    elements.retryButton.removeEventListener("click", handleRetry);
    optional.togglePasswordButton?.removeEventListener("click", togglePasswordVisibility);
    optional.mobileNavToggle?.removeEventListener("click", toggleMobileNav);
    optional.mobileNavClose?.removeEventListener("click", handleMobileNavDismiss);
    optional.mobileNavBackdrop?.removeEventListener("click", handleMobileNavDismiss);
    optional.dashboardNavButton?.removeEventListener("click", openDashboardHome);
    document.removeEventListener("keydown", handleKeydown);
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

function findOptionalElements(document) {
  return {
    togglePasswordButton: document.querySelector("#toggle-password-button"),
    mobileNavToggle: document.querySelector("#mobile-nav-toggle"),
    mobileNavClose: document.querySelector("#mobile-nav-close"),
    mobileNav: document.querySelector("#editorial-offcanvas-nav"),
    mobileNavBackdrop: document.querySelector("#mobile-nav-backdrop"),
    dashboardNavButton: document.querySelector("#dashboard-nav-button"),
    usersNavButton: document.querySelector("#users-nav-button"),
    rolesNavButton: document.querySelector("#roles-nav-button"),
    ownersNavButton: document.querySelector("#owners-nav-button"),
    usersBackButton: document.querySelector("#users-back-button"),
    rolesBackButton: document.querySelector("#roles-back-button"),
    ownersBackButton: document.querySelector("#owners-back-button"),
    dashboardHome: document.querySelector("#dashboard-home"),
    usersView: document.querySelector("#users-view"),
    rolesView: document.querySelector("#roles-view"),
    ownersView: document.querySelector("#owners-view")
  };
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
    || !Array.isArray(value.authorities)
    || !value.authorities.every((authority) => typeof authority === "string")
  ) {
    throw invalidResponse();
  }
  return Object.freeze({
    userId: value.userId,
    tenantId: value.tenantId,
    branchId: value.branchId,
    authorities: Object.freeze([...value.authorities])
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
