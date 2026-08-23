const CORRELATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ERROR_CODE_PATTERN = /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$/;

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
  let response;
  try {
    response = await fetch(input, {
      ...options,
      headers: {
        Accept: "application/json",
        ...(options.headers ?? {})
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
    if (isApiError(body)) {
      throw new ApiRequestError({
        kind: "HTTP",
        status: response.status,
        code: body.code,
        message: body.message,
        fieldErrors: body.fieldErrors,
        correlationId: body.correlationId
      });
    }
    throw new ApiRequestError({
      kind: "INVALID_RESPONSE",
      status: response.status,
      code: "INVALID_ERROR_RESPONSE",
      message: "El servidor devolvió una respuesta de error no reconocida."
    });
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
