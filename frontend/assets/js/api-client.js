const CORRELATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ERROR_CODE_PATTERN = /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$/;
const ACCESS_TOKEN_KEY = "petcare.accessToken";
let sessionRejectedHandler = null;

export class ApiRequestError extends Error {
  constructor({ kind, status = null, code, message, fieldErrors = [], correlationId = null }) {
    super(message);
    this.name = "ApiRequestError";
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.fieldErrors = Object.freeze(fieldErrors.map((error) => Object.freeze({ ...error })));
    this.correlationId = correlationId;
  }
}

export async function requestJson(input, options = {}) {
  const { useAccessToken = true, ...fetchOptions } = options;
  const accessToken = useAccessToken ? getAccessToken() : null;
  let response;
  try {
    response = await fetch(input, {
      ...fetchOptions,
      headers: {
        Accept: "application/json",
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...(fetchOptions.headers ?? {})
      }
    });
  } catch {
    throw new ApiRequestError({
      kind: "NETWORK",
      code: "NETWORK_ERROR",
      message: "No fue posible conectar con el servidor."
    });
  }

  const body = await parseJsonBody(response);
  if (!response.ok) {
    let error;
    if (isApiError(body)) {
      error = new ApiRequestError({
        kind: "HTTP",
        status: response.status,
        code: body.code,
        message: body.message,
        fieldErrors: body.fieldErrors,
        correlationId: body.correlationId
      });
    } else {
      error = new ApiRequestError({
        kind: "INVALID_RESPONSE",
        status: response.status,
        code: "INVALID_ERROR_RESPONSE",
        message: "El servidor devolvió una respuesta de error no reconocida.",
        correlationId: correlationIdFrom(response)
      });
    }
    rejectStoredSession(response.status, useAccessToken, accessToken, error);
    throw error;
  }

  if (response.status === 204) {
    return null;
  }
  if (body === null) {
    throw new ApiRequestError({
      kind: "INVALID_RESPONSE",
      status: response.status,
      code: "INVALID_RESPONSE",
      message: "El servidor devolvió una respuesta no válida."
    });
  }
  return body;
}

export function setAccessToken(token) {
  if (typeof token !== "string" || token.length === 0) {
    throw new TypeError("El access token no es válido.");
  }
  sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
}

export function clearAccessToken() {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function hasAccessToken() {
  return getAccessToken() !== null;
}

export function setSessionRejectedHandler(handler) {
  if (handler !== null && typeof handler !== "function") {
    throw new TypeError("El manejador de sesión rechazada debe ser una función.");
  }
  sessionRejectedHandler = handler;
  return () => {
    if (sessionRejectedHandler === handler) {
      sessionRejectedHandler = null;
    }
  };
}

function getAccessToken() {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY);
}

function rejectStoredSession(status, useAccessToken, accessToken, error) {
  if (status !== 401 || !useAccessToken || accessToken === null || getAccessToken() !== accessToken) {
    return;
  }
  clearAccessToken();
  sessionRejectedHandler?.(error);
}

export function isApiError(value) {
  return Boolean(
    value
      && typeof value === "object"
      && ERROR_CODE_PATTERN.test(value.code)
      && typeof value.message === "string"
      && value.message.length > 0
      && Array.isArray(value.fieldErrors)
      && value.fieldErrors.every(isFieldError)
      && typeof value.correlationId === "string"
      && CORRELATION_ID_PATTERN.test(value.correlationId)
  );
}

async function parseJsonBody(response) {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) {
    return null;
  }
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function correlationIdFrom(response) {
  const value = response.headers.get("X-Correlation-ID");
  return value !== null && CORRELATION_ID_PATTERN.test(value) ? value : null;
}

function isFieldError(value) {
  return Boolean(
    value
      && typeof value === "object"
      && typeof value.field === "string"
      && value.field.length > 0
      && ERROR_CODE_PATTERN.test(value.code)
      && typeof value.message === "string"
      && value.message.length > 0
  );
}
