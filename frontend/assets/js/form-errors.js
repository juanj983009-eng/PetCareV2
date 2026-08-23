const CONTROL_MARKER = "data-api-error-control";
const FIELD_ERROR_MARKER = "data-api-field-error";
const SUMMARY_MARKER = "data-api-error-summary";
const previousControlState = new WeakMap();
let messageSequence = 0;

export function applyApiError(form, apiError, summaryContainer) {
  assertElement(form, "form");
  assertElement(summaryContainer, "summaryContainer");
  clearApiErrors(form, summaryContainer);

  const unmatchedErrors = [];
  for (const fieldError of apiError.fieldErrors ?? []) {
    const controls = controlsNamed(form, fieldError.field);
    if (controls.length === 0) {
      unmatchedErrors.push(fieldError);
      continue;
    }
    const messageId = nextMessageId();
    for (const control of controls) {
      markControl(control, messageId);
    }
    const message = document.createElement("div");
    message.id = messageId;
    message.classList.add("invalid-feedback", "d-block");
    message.setAttribute(FIELD_ERROR_MARKER, "true");
    message.textContent = fieldError.message;
    controls.at(-1).insertAdjacentElement("afterend", message);
  }

  const summary = document.createElement("div");
  summary.classList.add("alert", "alert-danger");
  summary.setAttribute(SUMMARY_MARKER, "true");
  summary.setAttribute("role", "alert");

  const title = document.createElement("p");
  title.classList.add("mb-0");
  title.textContent = apiError.message || "No fue posible completar la operación.";
  summary.append(title);

  if (unmatchedErrors.length > 0) {
    const list = document.createElement("ul");
    list.classList.add("mb-0", "mt-2");
    for (const fieldError of unmatchedErrors) {
      const item = document.createElement("li");
      item.textContent = fieldError.message;
      list.append(item);
    }
    summary.append(list);
  }

  summaryContainer.append(summary);
  return summary;
}

export function clearApiErrors(form, summaryContainer) {
  assertElement(form, "form");
  assertElement(summaryContainer, "summaryContainer");

  for (const message of form.querySelectorAll(`[${FIELD_ERROR_MARKER}]`)) {
    message.remove();
  }
  for (const control of form.querySelectorAll(`[${CONTROL_MARKER}]`)) {
    restoreControl(control);
  }
  for (const summary of summaryContainer.querySelectorAll(`[${SUMMARY_MARKER}]`)) {
    summary.remove();
  }
}

function controlsNamed(form, fieldName) {
  return Array.from(form.elements).filter((control) => control.name === fieldName);
}

function markControl(control, messageId) {
  if (!previousControlState.has(control)) {
    previousControlState.set(control, {
      ariaInvalid: control.getAttribute("aria-invalid"),
      ariaDescribedBy: control.getAttribute("aria-describedby"),
      hadInvalidClass: control.classList.contains("is-invalid")
    });
  }
  const describedBy = new Set((control.getAttribute("aria-describedby") ?? "").split(/\s+/).filter(Boolean));
  describedBy.add(messageId);
  control.setAttribute("aria-describedby", Array.from(describedBy).join(" "));
  control.setAttribute("aria-invalid", "true");
  control.setAttribute(CONTROL_MARKER, "true");
  control.classList.add("is-invalid");
}

function restoreControl(control) {
  const previous = previousControlState.get(control);
  if (previous) {
    restoreAttribute(control, "aria-invalid", previous.ariaInvalid);
    restoreAttribute(control, "aria-describedby", previous.ariaDescribedBy);
    if (!previous.hadInvalidClass) {
      control.classList.remove("is-invalid");
    }
    previousControlState.delete(control);
  }
  control.removeAttribute(CONTROL_MARKER);
}

function restoreAttribute(element, name, value) {
  if (value === null) {
    element.removeAttribute(name);
  } else {
    element.setAttribute(name, value);
  }
}

function nextMessageId() {
  messageSequence += 1;
  return `api-field-error-${messageSequence}`;
}

function assertElement(value, name) {
  if (!(value instanceof Element)) {
    throw new TypeError(`${name} debe ser un elemento del DOM.`);
  }
}
