import { ApiRequestError } from "../assets/js/api-client.js";
import { createAuthController } from "../assets/js/auth-controller.js";
import { applyApiError, clearApiErrors } from "../assets/js/form-errors.js";
import { createUsersController } from "../assets/js/users-controller.js";
import { createRolesController } from "../assets/js/roles-controller.js";
import { createOwnersController } from "../assets/js/owners-controller.js";
import { createPatientsController } from "../assets/js/patients-controller.js";

const USER_ID = "11111111-1111-4111-8111-111111111111";
const TENANT_ID = "22222222-2222-4222-8222-222222222222";
const BRANCH_ID = "33333333-3333-4333-8333-333333333333";
const CORRELATION_ID = "55555555-5555-4555-8555-555555555555";
const tests = [];

test("asociación accesible de campos y control de contraseña en login y formularios", async () => {
  const requiredAssociations = [
    ["clinicSlug", "clinicSlugFeedback"],
    ["email", "emailFeedback"],
    ["password", "passwordFeedback"],
    ["owner-search", "owner-search-feedback"],
    ["user-first-name", "user-first-name-feedback"],
    ["user-last-name", "user-last-name-feedback"],
    ["user-email", "user-email-feedback"]
  ];
  for (const [fieldId, feedbackId] of requiredAssociations) {
    const control = document.querySelector(`#${fieldId}`);
    const label = document.querySelector(`label[for="${fieldId}"]`);
    const describedBy = control?.getAttribute("aria-describedby") ?? "";
    assert(Boolean(label), `Falta label para #${fieldId}`);
    assert(describedBy.split(/\s+/).includes(feedbackId), `#${fieldId} no referencia #${feedbackId}`);
    assert(Boolean(document.querySelector(`#${feedbackId}`)), `No existe #${feedbackId}`);
  }

  const { auth } = setupSuite();
  await auth.start();
  const password = document.querySelector("#password");
  const toggle = document.querySelector("#toggle-password-button");
  equal(password.type, "password");
  equal(toggle.getAttribute("aria-pressed"), "false");
  toggle.click();
  equal(password.type, "text");
  equal(toggle.getAttribute("aria-pressed"), "true");
  auth.logout();
  equal(password.type, "password");
  equal(toggle.getAttribute("aria-pressed"), "false");
  auth.destroy();
});

test("navegación responsive abre/cierra menú móvil y conmuta vistas sin solapamiento", async () => {
  const { auth } = setupSuite({
    token: "valid-token",
    authorities: ["DASHBOARD_VIEW", "USER_READ", "ROLE_READ", "OWNER_READ"]
  });
  await auth.start();

  const toggle = document.querySelector("#mobile-nav-toggle");
  const nav = document.querySelector("#editorial-offcanvas-nav");
  const backdrop = document.querySelector("#mobile-nav-backdrop");

  equal(toggle.getAttribute("aria-expanded"), "false");
  assert(!nav.classList.contains("is-open"));
  toggle.click();
  equal(toggle.getAttribute("aria-expanded"), "true");
  assert(nav.classList.contains("is-open"));
  assert(!backdrop.hidden);

  document.querySelector("#users-nav-button").click();
  assert(!nav.classList.contains("is-open"));
  equal(toggle.getAttribute("aria-expanded"), "false");
  assert(!document.querySelector("#users-view").hidden);
  assert(document.querySelector("#roles-view").hidden);
  assert(document.querySelector("#owners-view").hidden);
  equal(document.querySelector("#users-nav-button").getAttribute("aria-current"), "page");

  document.querySelector("#roles-nav-button").click();
  assert(document.querySelector("#users-view").hidden);
  assert(!document.querySelector("#roles-view").hidden);
  assert(document.querySelector("#owners-view").hidden);
  equal(document.querySelector("#roles-nav-button").getAttribute("aria-current"), "page");

  document.querySelector("#dashboard-nav-button").click();
  assert(!document.querySelector("#dashboard-home").hidden);
  assert(document.querySelector("#users-view").hidden);
  assert(document.querySelector("#roles-view").hidden);
  assert(document.querySelector("#owners-view").hidden);
  equal(document.querySelector("#dashboard-nav-button").getAttribute("aria-current"), "page");

  auth.destroy();
});

test("visibilidad basada en permisos oculta módulos no autorizados", async () => {
  const restricted = setupSuite({
    token: "restricted-token",
    authorities: ["DASHBOARD_VIEW", "OWNER_READ"]
  });
  await restricted.auth.start();
  assert(document.querySelector("#users-nav-button").hidden);
  assert(document.querySelector("#roles-nav-button").hidden);
  assert(!document.querySelector("#owners-nav-button").hidden);
  assert(document.querySelector("#new-owner-button").hidden);
  assert(document.querySelector("#new-patient-button").hidden);
  restricted.auth.destroy();
});

test("preservación de sesión, logout seguro y estados de error correlacionados", async () => {
  const { auth, client } = setupSuite({
    token: "active-token",
    authorities: ["DASHBOARD_VIEW", "USER_READ"]
  });
  await auth.start();
  equal(auth.snapshot().status, "AUTHENTICATED");
  assert(!document.querySelector("#dashboard-view").hidden);

  auth.logout();
  equal(client.token, null);
  equal(auth.snapshot().status, "ANONYMOUS");
  assert(!document.querySelector("#login-view").hidden);
  assert(document.querySelector("#users-nav-button").hidden);

  const loginForm = document.querySelector("#login-form");
  const loginErrors = document.querySelector("#login-errors");
  applyApiError(loginForm, new ApiRequestError({
    kind: "HTTP",
    status: 401,
    code: "INVALID_CREDENTIALS",
    message: "Las credenciales proporcionadas no son válidas.",
    correlationId: CORRELATION_ID,
    fieldErrors: [{ field: "email", code: "INVALID_EMAIL", message: "Formato inválido" }]
  }), loginErrors);

  assert(loginErrors.textContent.includes(CORRELATION_ID));
  assert(document.querySelector("#email").classList.contains("is-invalid"));
  equal(document.querySelector("#email").getAttribute("aria-invalid"), "true");
  clearApiErrors(loginForm, loginErrors);
  auth.destroy();
});

function setupSuite({ token = null, authorities = ["DASHBOARD_VIEW"] } = {}) {
  const client = {
    token,
    calls: [],
    requestJson: async (input) => {
      client.calls.push(input);
      if (input === "/api/v1/auth/session") {
        return { userId: USER_ID, tenantId: TENANT_ID, branchId: BRANCH_ID, authorities };
      }
      if (input === "/api/v1/users/options") {
        return { roles: [], branches: [] };
      }
      if (input.startsWith("/api/v1/users")) {
        return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
      }
      if (input === "/api/v1/roles") {
        return { roles: [], availablePermissions: [] };
      }
      return { content: [], page: 0, totalPages: 0, totalElements: 0 };
    },
    setAccessToken(value) { this.token = value; },
    clearAccessToken() { this.token = null; },
    hasAccessToken() { return this.token !== null; },
    setSessionRejectedHandler() { return () => {}; }
  };

  const errors = { applyApiError, clearApiErrors };
  const users = createUsersController({ document, client, errors });
  const roles = createRolesController({ document, client });
  const owners = createOwnersController({ document, client });
  const patients = createPatientsController({ document, client });
  const auth = createAuthController({
    document,
    window,
    client,
    errors,
    lifecycle: {
      authenticated: (identity) => {
        users.sessionReady(identity);
        roles.sessionReady(identity);
        owners.sessionReady(identity);
        patients.sessionReady(identity);
      },
      ended: () => {
        users.sessionEnded();
        roles.sessionEnded();
        owners.sessionEnded();
        patients.sessionEnded();
      }
    }
  });
  return { auth, client };
}

function test(name, body) {
  tests.push({ name, body });
}

function equal(actual, expected) {
  assert(actual === expected, `Esperado ${String(expected)}, recibido ${String(actual)}`);
}

function assert(condition, message = "Aserción fallida") {
  if (!condition) {
    throw new Error(message);
  }
}

async function run() {
  const results = document.querySelector("#test-results");
  let passed = 0;
  for (const entry of tests) {
    const item = document.createElement("li");
    try {
      await entry.body();
      item.className = "passed";
      item.textContent = `OK — ${entry.name}`;
      passed += 1;
    } catch (error) {
      item.className = "failed";
      item.textContent = `FALLO — ${entry.name}: ${error.message}`;
    }
    results.append(item);
  }
  const summary = document.querySelector("#test-summary");
  summary.textContent = `${passed}/${tests.length} pruebas aprobadas`;
  summary.className = passed === tests.length ? "passed" : "failed";
  document.documentElement.dataset.testStatus = passed === tests.length ? "passed" : "failed";
}

await run();
