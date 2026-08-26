import {
  ApiRequestError,
  clearAccessToken,
  hasAccessToken,
  requestJson,
  setAccessToken,
  setSessionRejectedHandler
} from "../assets/js/api-client.js";
import { createAuthController } from "../assets/js/auth-controller.js";
import { applyApiError, clearApiErrors } from "../assets/js/form-errors.js";

const USER_ID = "11111111-1111-4111-8111-111111111111";
const TENANT_ID = "22222222-2222-4222-8222-222222222222";
const BRANCH_ID = "33333333-3333-4333-8333-333333333333";
const CORRELATION_ID = "44444444-4444-4444-8444-444444444444";
const originalFetch = globalThis.fetch;
const tests = [];

test("recarga sin token muestra login sin petición", async () => {
  const { controller, client } = setup();
  await controller.start();
  equal(controller.snapshot().status, "ANONYMOUS");
  equal(client.calls.length, 0);
  visible("#login-view");
  controller.destroy();
});

test("token válido restaura identidad y dashboard", async () => {
  const { controller, client } = setup({ token: "valid-test-token" });
  client.behavior = () => sessionResponse();
  await controller.start();
  equal(controller.snapshot().status, "AUTHENTICATED");
  equal(controller.snapshot().identity.tenantId, TENANT_ID);
  visible("#dashboard-view");
  equal(client.calls.filter((call) => call.input === "/api/v1/auth/session").length, 1);
  controller.destroy();
});

test("logout elimina token e identidad, vuelve al login y es idempotente", async () => {
  const { controller, client } = setup({ token: "valid-test-token" });
  client.behavior = () => sessionResponse();
  await controller.start();
  document.querySelector("#password").value = "temporary-password";
  controller.logout();
  controller.logout();
  equal(client.token, null);
  equal(controller.snapshot().identity, null);
  equal(controller.snapshot().status, "ANONYMOUS");
  equal(document.querySelector("#password").value, "");
  visible("#login-view");
  hidden("#dashboard-view");
  assert(!document.querySelector("#login-errors").textContent.includes("expiró"));
  controller.destroy();
});

test("401 de sesión limpia identidad y muestra expiración correlacionada", async () => {
  const { controller, client } = setup({ token: "expired-test-token" });
  const error = apiError(401, "UNAUTHORIZED", "Debes autenticarte para continuar.");
  client.behavior = () => client.rejectSession(error);
  await controller.start();
  equal(client.token, null);
  equal(controller.snapshot().status, "SESSION_EXPIRED");
  equal(controller.snapshot().identity, null);
  visible("#login-view");
  assert(document.querySelector("#login-errors").textContent.includes("Tu sesión expiró"));
  assert(document.querySelector("#login-errors").textContent.includes(CORRELATION_ID));
  controller.destroy();
});

test("token manipulado sigue el mismo cierre seguro", async () => {
  const { controller, client } = setup({ token: "manipulated-test-token" });
  client.behavior = () => client.rejectSession(apiError(401, "UNAUTHORIZED", "Rechazado"));
  await controller.start();
  equal(controller.snapshot().status, "SESSION_EXPIRED");
  equal(client.token, null);
  controller.destroy();
});

test("403 conserva token e identidad local y permite reintentar", async () => {
  const { controller, client } = setup({ token: "valid-test-token" });
  client.behavior = () => Promise.reject(apiError(403, "ACCESS_DENIED", "Acceso denegado"));
  await controller.start();
  equal(client.token, "valid-test-token");
  equal(controller.snapshot().status, "ACCESS_DENIED");
  visible("#session-validation-error-view");
  controller.destroy();
});

test("error de red conserva token y no simula expiración", async () => {
  const { controller, client } = setup({ token: "valid-test-token" });
  client.behavior = () => Promise.reject(new ApiRequestError({
    kind: "NETWORK",
    code: "NETWORK_ERROR",
    message: "Sin conexión"
  }));
  await controller.start();
  equal(client.token, "valid-test-token");
  equal(controller.snapshot().status, "VALIDATION_FAILED");
  assert(!document.querySelector("#session-validation-error-message").textContent.includes("expiró"));
  controller.destroy();
});

test("restauraciones simultáneas realizan una sola petición", async () => {
  const deferred = createDeferred();
  const { controller, client } = setup({ token: "valid-test-token" });
  client.behavior = () => deferred.promise;
  const first = controller.start();
  const second = controller.restoreSession();
  equal(client.calls.length, 1);
  deferred.resolve(sessionResponse());
  await Promise.all([first, second]);
  equal(controller.snapshot().status, "AUTHENTICATED");
  controller.destroy();
});

test("una respuesta tardía no restaura dashboard tras logout", async () => {
  const deferred = createDeferred();
  const { controller, client } = setup({ token: "valid-test-token" });
  client.behavior = () => deferred.promise;
  const validation = controller.start();
  controller.logout();
  deferred.resolve(sessionResponse());
  await validation;
  equal(controller.snapshot().status, "ANONYMOUS");
  hidden("#dashboard-view");
  visible("#login-view");
  controller.destroy();
});

test("credenciales inválidas no se presentan como expiración", async () => {
  const { controller, client } = setup();
  client.behavior = () => Promise.reject(apiError(
    401,
    "INVALID_CREDENTIALS",
    "Las credenciales proporcionadas no son válidas."
  ));
  await controller.start();
  fillValidLogin();
  document.querySelector("#login-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTask();
  assert(document.querySelector("#login-errors").textContent.includes("credenciales"));
  assert(!document.querySelector("#login-errors").textContent.includes("expiró"));
  equal(document.querySelector("#password").value, "");
  equal(client.calls.length, 1);
  controller.destroy();
});

test("doble envío de login no duplica solicitudes", async () => {
  const deferred = createDeferred();
  const { controller, client } = setup();
  client.behavior = (input) => input.endsWith("/login") ? deferred.promise : sessionResponse();
  await controller.start();
  fillValidLogin();
  const submit = () => document.querySelector("#login-form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  submit();
  submit();
  equal(client.calls.filter((call) => call.input.endsWith("/login")).length, 1);
  deferred.resolve(loginResponse());
  await waitFor(() => controller.snapshot().status === "AUTHENTICATED");
  equal(client.calls.filter((call) => call.input.endsWith("/login")).length, 1);
  controller.destroy();
});

test("mensajes hostiles se representan como texto", async () => {
  const hostile = '<img src=x onerror="globalThis.compromised=true">';
  const { controller, client } = setup();
  client.behavior = () => Promise.reject(apiError(401, "INVALID_CREDENTIALS", hostile));
  await controller.start();
  fillValidLogin();
  document.querySelector("#login-form").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTask();
  assert(document.querySelector("#login-errors").textContent.includes(hostile));
  equal(document.querySelector("#login-errors img"), null);
  equal(globalThis.compromised, undefined);
  controller.destroy();
});

test("cliente HTTP centraliza 401 protegido y conserva correlation ID", async () => {
  clearAccessToken();
  setAccessToken("centralized-test-token");
  let rejection = null;
  const remove = setSessionRejectedHandler((error) => { rejection = error; });
  globalThis.fetch = async () => jsonResponse(401, {
    code: "UNAUTHORIZED",
    message: "Debes autenticarte para continuar.",
    fieldErrors: [],
    correlationId: CORRELATION_ID
  });
  await expectApiError(() => requestJson("/protected"), 401);
  assert(!hasAccessToken());
  equal(rejection.correlationId, CORRELATION_ID);
  remove();
});

test("401 del login, 403 y red no eliminan una sesión existente", async () => {
  setAccessToken("retained-test-token");
  let rejected = 0;
  const remove = setSessionRejectedHandler(() => { rejected += 1; });

  globalThis.fetch = async () => jsonResponse(401, {
    code: "INVALID_CREDENTIALS",
    message: "Las credenciales proporcionadas no son válidas.",
    fieldErrors: [],
    correlationId: CORRELATION_ID
  });
  await expectApiError(() => requestJson("/api/v1/auth/login", { useAccessToken: false }), 401);
  assert(hasAccessToken());

  globalThis.fetch = async () => jsonResponse(403, {
    code: "ACCESS_DENIED",
    message: "No tienes permiso.",
    fieldErrors: [],
    correlationId: CORRELATION_ID
  });
  await expectApiError(() => requestJson("/protected"), 403);
  assert(hasAccessToken());

  globalThis.fetch = async () => { throw new TypeError("offline"); };
  await expectApiError(() => requestJson("/protected"), null);
  assert(hasAccessToken());
  equal(rejected, 0);
  remove();
});

test("respuesta inválida y 401 antiguo no destruyen token nuevo", async () => {
  setAccessToken("old-test-token");
  const deferred = createDeferred();
  globalThis.fetch = () => deferred.promise;
  const oldRequest = requestJson("/protected");
  setAccessToken("new-test-token");
  deferred.resolve(jsonResponse(401, {
    code: "UNAUTHORIZED",
    message: "Debes autenticarte para continuar.",
    fieldErrors: [],
    correlationId: CORRELATION_ID
  }));
  await expectApiError(() => oldRequest, 401);
  assert(hasAccessToken());

  globalThis.fetch = async () => new Response("not-json", {
    status: 200,
    headers: { "Content-Type": "text/plain" }
  });
  await expectApiError(() => requestJson("/protected"), 200);
  assert(hasAccessToken());
});

async function run() {
  const results = document.querySelector("#test-results");
  let passed = 0;
  try {
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
  } finally {
    globalThis.fetch = originalFetch;
    clearAccessToken();
  }
  const summary = document.querySelector("#test-summary");
  summary.textContent = `${passed}/${tests.length} pruebas aprobadas`;
  summary.className = passed === tests.length ? "passed" : "failed";
  document.documentElement.dataset.testStatus = passed === tests.length ? "passed" : "failed";
}

function test(name, body) {
  tests.push({ name, body });
}

function setup({ token = null } = {}) {
  resetFixture();
  const client = new FakeClient(token);
  const controller = createAuthController({
    document,
    window,
    client,
    errors: { applyApiError, clearApiErrors }
  });
  return { controller, client };
}

class FakeClient {
  constructor(token) {
    this.token = token;
    this.handler = null;
    this.calls = [];
    this.behavior = () => sessionResponse();
  }

  requestJson(input, options = {}) {
    this.calls.push({ input, options });
    return Promise.resolve().then(() => this.behavior(input, options));
  }

  setAccessToken(token) { this.token = token; }
  clearAccessToken() { this.token = null; }
  hasAccessToken() { return this.token !== null; }

  setSessionRejectedHandler(handler) {
    this.handler = handler;
    return () => {
      if (this.handler === handler) {
        this.handler = null;
      }
    };
  }

  rejectSession(error) {
    this.token = null;
    this.handler?.(error);
    return Promise.reject(error);
  }
}

function resetFixture() {
  clearApiErrors(document.querySelector("#login-form"), document.querySelector("#login-errors"));
  document.querySelector("#login-form").reset();
  document.querySelector("#login-form").className = "";
  document.querySelector("#authority-list").replaceChildren();
  document.querySelector("#session-validation-error-message").textContent = "";
  document.querySelector("#session-validation-correlation").textContent = "";
  for (const selector of [
    "#login-view",
    "#session-validation-view",
    "#session-validation-error-view",
    "#dashboard-view"
  ]) {
    document.querySelector(selector).hidden = true;
  }
}

function fillValidLogin() {
  document.querySelector("#clinicSlug").value = "fictional-clinic";
  document.querySelector("#email").value = "admin@example.test";
  document.querySelector("#password").value = "Fictional-Password-123!";
}

function loginResponse() {
  return {
    accessToken: "issued-test-token",
    tokenType: "Bearer",
    expiresAt: "2026-08-25T20:00:00Z",
    identity: {
      userId: USER_ID,
      tenantId: TENANT_ID,
      branchId: BRANCH_ID,
      authorities: ["DASHBOARD_VIEW"]
    }
  };
}

function sessionResponse() {
  return { userId: USER_ID, tenantId: TENANT_ID, branchId: BRANCH_ID };
}

function apiError(status, code, message) {
  return new ApiRequestError({
    kind: "HTTP",
    status,
    code,
    message,
    correlationId: CORRELATION_ID
  });
}

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "X-Correlation-ID": CORRELATION_ID }
  });
}

async function expectApiError(action, status) {
  try {
    await action();
    throw new Error("Se esperaba ApiRequestError");
  } catch (error) {
    assert(error instanceof ApiRequestError);
    if (status !== null) {
      equal(error.status, status);
    }
  }
}

function createDeferred() {
  let resolve;
  let reject;
  const promise = new Promise((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

async function waitFor(predicate) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) {
      return;
    }
    await nextTask();
  }
  throw new Error("Se agotó el tiempo de espera");
}

function nextTask() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function visible(selector) {
  assert(!document.querySelector(selector).hidden, `${selector} debería estar visible`);
}

function hidden(selector) {
  assert(document.querySelector(selector).hidden, `${selector} debería estar oculto`);
}

function equal(actual, expected) {
  assert(actual === expected, `Esperado ${String(expected)}, recibido ${String(actual)}`);
}

function assert(condition, message = "Aserción incumplida") {
  if (!condition) {
    throw new Error(message);
  }
}

await run();
