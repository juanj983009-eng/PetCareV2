import { ApiRequestError } from "../assets/js/api-client.js";
import { applyApiError, clearApiErrors } from "../assets/js/form-errors.js";
import { createUsersController } from "../assets/js/users-controller.js";

const USER_ID = "11111111-1111-4111-8111-111111111111";
const ROLE_ID = "22222222-2222-4222-8222-222222222222";
const BRANCH_ID = "33333333-3333-4333-8333-333333333333";
const tests = [];

test("oculta la gestión sin permisos", async () => {
  const { controller } = setup([]);
  assert(document.querySelector("#users-nav-button").hidden);
  controller.destroy();
});

test("USER_READ lista usuarios pero no ofrece mutaciones", async () => {
  const { controller, client } = setup(["USER_READ"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  assert(!document.querySelector("#users-content").hidden);
  assert(document.querySelector("#new-user-button").hidden);
  assert(document.querySelector("#users-table-body").textContent.includes("Ada Lovelace"));
  assert(document.querySelector("#users-table-body").textContent.includes("Ver"));
  controller.destroy();
});

test("USER_MANAGE habilita registro y controles de estado", async () => {
  const { controller, client } = setup(["USER_READ", "USER_MANAGE"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  assert(!document.querySelector("#new-user-button").hidden);
  assert(document.querySelector("#users-table-body").textContent.includes("Editar"));
  assert(document.querySelector("#users-table-body").textContent.includes("Desactivar"));
  controller.destroy();
});

test("envía búsqueda y filtros como parámetros permitidos", async () => {
  const { controller, client } = setup(["USER_READ"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  document.querySelector("#user-search").value = "ada@example.test";
  document.querySelector("#user-status-filter").value = "ACTIVO";
  submit("#user-filters");
  await waitFor(() => client.calls.filter((call) => call.input.startsWith("/api/v1/users?")).length === 2);
  const input = client.calls.at(-1).input;
  assert(input.includes("search=ada%40example.test"));
  assert(input.includes("status=ACTIVO"));
  assert(!input.includes("tenant"));
  controller.destroy();
});

test("registro envía contraseña inicial una vez y la limpia", async () => {
  const { controller, client } = setup(["USER_READ", "USER_MANAGE"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  click("#new-user-button");
  fillForm();
  submit("#user-form");
  await waitFor(() => client.calls.some((call) => call.options.method === "POST"));
  const call = client.calls.find((entry) => entry.options.method === "POST");
  equal(JSON.parse(call.options.body).initialPassword, "Fictional-Password-123!");
  equal(document.querySelector("#user-initial-password").value, "");
  controller.destroy();
});

test("doble envío no duplica el registro", async () => {
  const { controller, client } = setup(["USER_READ", "USER_MANAGE"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  click("#new-user-button");
  fillForm();
  submit("#user-form");
  submit("#user-form");
  await waitFor(() => client.calls.some((call) => call.options.method === "POST"));
  equal(client.calls.filter((call) => call.options.method === "POST").length, 1);
  controller.destroy();
});

test("edición no envía ni muestra contraseña", async () => {
  const { controller, client } = setup(["USER_READ", "USER_MANAGE"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  document.querySelector("#users-table-body button").click();
  await waitFor(() => document.querySelector("#user-email").value !== "");
  assert(document.querySelector("#user-password-group").hidden);
  document.querySelector("#user-first-name").value = "Augusta";
  submit("#user-form");
  await waitFor(() => client.calls.some((call) => call.options.method === "PUT"));
  const body = JSON.parse(client.calls.find((call) => call.options.method === "PUT").options.body);
  assert(!Object.hasOwn(body, "initialPassword"));
  equal(body.version, 0);
  controller.destroy();
});

test("confirma la desactivación con la versión conocida", async () => {
  const { controller, client } = setup(["USER_READ", "USER_MANAGE"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  const buttons = Array.from(document.querySelectorAll("#users-table-body button"));
  buttons.find((button) => button.textContent === "Desactivar").click();
  click("#confirm-user-status");
  await waitFor(() => client.calls.some((call) => call.input.endsWith("/deactivate")));
  const call = client.calls.find((entry) => entry.input.endsWith("/deactivate"));
  equal(JSON.parse(call.options.body).version, 0);
  controller.destroy();
});

test("los datos hostiles se insertan como texto", async () => {
  const hostile = '<img src=x onerror="globalThis.compromised=true">';
  const { controller, client } = setup(["USER_READ"], { ...user(), firstName: hostile });
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  assert(document.querySelector("#users-table-body").textContent.includes(hostile));
  equal(document.querySelector("#users-table-body img"), null);
  equal(globalThis.compromised, undefined);
  controller.destroy();
});

test("un error de formulario conserva los valores y muestra correlation ID", async () => {
  const { controller, client } = setup(["USER_READ", "USER_MANAGE"]);
  click("#users-nav-button");
  await waitFor(() => client.calls.length === 2);
  click("#new-user-button");
  fillForm();
  client.createError = new ApiRequestError({
    kind: "HTTP", status: 409, code: "USER_EMAIL_ALREADY_EXISTS",
    message: "El correo ya está registrado.", correlationId: "44444444-4444-4444-8444-444444444444",
    fieldErrors: [{ field: "email", message: "Correo duplicado" }]
  });
  submit("#user-form");
  await waitFor(() => document.querySelector("#user-form-errors").textContent.includes("seguimiento"));
  equal(document.querySelector("#user-first-name").value, "Grace");
  assert(document.querySelector("#user-email").classList.contains("is-invalid"));
  controller.destroy();
});

async function run() {
  const results = document.querySelector("#test-results");
  let passed = 0;
  for (const entry of tests) {
    resetFixture();
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

function setup(authorities, listedUser = user()) {
  const client = new FakeClient(listedUser);
  const controller = createUsersController({ document, client, errors: { applyApiError, clearApiErrors } });
  controller.sessionReady({ authorities });
  return { controller, client };
}

class FakeClient {
  constructor(listedUser) { this.listedUser = listedUser; this.calls = []; this.createError = null; }
  requestJson(input, options = {}) {
    this.calls.push({ input, options });
    if (options.method === "POST" && input === "/api/v1/users" && this.createError) {
      return Promise.reject(this.createError);
    }
    if (input === "/api/v1/users/options") return Promise.resolve(optionsResponse());
    if (input.startsWith("/api/v1/users?") ) return Promise.resolve(pageResponse(this.listedUser));
    return Promise.resolve(this.listedUser);
  }
}

function user() {
  return { id: USER_ID, firstName: "Ada", lastName: "Lovelace", email: "ada@example.test", status: "ACTIVO",
    role: { id: ROLE_ID, name: "ADMINISTRADOR" }, branch: { id: BRANCH_ID, name: "Sede Central", active: true },
    createdAt: "2026-08-27T10:00:00Z", updatedAt: "2026-08-27T10:00:00Z", version: 0 };
}

function optionsResponse() {
  return { roles: [{ id: ROLE_ID, name: "ADMINISTRADOR" }], branches: [{ id: BRANCH_ID, name: "Sede Central", active: true }] };
}

function pageResponse(content) {
  return { content: [content], page: 0, size: 10, totalElements: 1, totalPages: 1 };
}

function fillForm() {
  document.querySelector("#user-first-name").value = "Grace";
  document.querySelector("#user-last-name").value = "Hopper";
  document.querySelector("#user-email").value = "grace@example.test";
  document.querySelector("#user-initial-password").value = "Fictional-Password-123!";
  document.querySelector("#user-role").value = ROLE_ID;
  document.querySelector("#user-branch").value = BRANCH_ID;
}

function resetFixture() {
  document.querySelector("#user-form").reset();
  clearApiErrors(document.querySelector("#user-form"), document.querySelector("#user-form-errors"));
  for (const dialog of document.querySelectorAll("dialog")) if (dialog.open) dialog.close();
  document.querySelector("#users-table-body").replaceChildren();
  document.querySelector("#users-cards").replaceChildren();
  document.querySelector("#users-nav-button").hidden = true;
  document.querySelector("#new-user-button").hidden = true;
  document.querySelector("#users-view").hidden = true;
  document.querySelector("#dashboard-home").hidden = false;
}

function click(selector) { document.querySelector(selector).click(); }
function submit(selector) { document.querySelector(selector).dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); }
function test(name, body) { tests.push({ name, body }); }
async function waitFor(predicate) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error("Se agotó el tiempo de espera");
}
function equal(actual, expected) { assert(actual === expected, `Esperado ${String(expected)}, recibido ${String(actual)}`); }
function assert(condition, message = "Aserción incumplida") { if (!condition) throw new Error(message); }

await run();
