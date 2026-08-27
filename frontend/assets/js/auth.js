import * as apiClient from "./api-client.js";
import { createAuthController } from "./auth-controller.js";
import { applyApiError, clearApiErrors } from "./form-errors.js";
import { createUsersController } from "./users-controller.js";

const usersController = createUsersController({
  document,
  client: apiClient,
  errors: { applyApiError, clearApiErrors }
});

const authController = createAuthController({
  document,
  window,
  client: apiClient,
  errors: { applyApiError, clearApiErrors },
  lifecycle: {
    authenticated: usersController.sessionReady,
    ended: usersController.sessionEnded
  }
});

authController.start();
