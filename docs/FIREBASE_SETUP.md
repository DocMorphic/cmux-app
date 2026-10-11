# Dedicated Firebase setup — 2026-10-11

The user explicitly selected private Firebase plus a custom cmux Mac extension,
then authorized creating a dedicated project.

## Current state

- Dedicated Google Cloud project: `cmux-app-push-20261011`, display name `cmux-app`.
- Project number: `556314637658`; lifecycle `ACTIVE` verified.
- Created using the Mac’s active gcloud account. The account has project Owner;
  the new project inherited that account’s Google Cloud organization.
- Firebase Management API (`firebase.googleapis.com`) and FCM API
  (`fcm.googleapis.com`) enabled successfully, scoped explicitly to this project.
- `billingEnabled: false` verified. No Analytics link was created.
- **Adding Firebase has not succeeded.** The REST request with an explicit quota
  header (`x-goog-user-project: cmux-app-push-20261011`) returns HTTP 403:
  “The caller does not have permission.” No Firebase apps, client configuration,
  provider service account/key or live sender have been created.

The initial default Python TLS trust store failed before sending the request;
Node’s verified HTTPS transport succeeded. An initial REST request without the
quota header returned the quota-project error. No TLS verification was disabled,
no credentials were printed, and no unrelated existing project was modified.

## Account owner step requested

Open [Firebase console](https://console.firebase.google.com/) with the same account
used by gcloud. Accept Firebase’s terms if prompted and add Firebase to the
**existing** project `cmux-app-push-20261011`, keeping Analytics off.
Firebase’s [existing-project guide](https://firebase.google.com/docs/projects/use-firebase-with-existing-cloud-project)
says HTTP 403 can mean missing permissions or unaccepted Firebase terms. Owner
was verified; unaccepted terms are a plausible cause, not an observed diagnosis.
If the console shows a different error, record it rather than creating another
project or changing organization policy.

## Next implementation

1. Verify the project is a FirebaseProject before further provisioning.
2. Register Android clients for `io.github.docmorphic.cmuxapp` and
   `io.github.docmorphic.cmuxapp.debug`; preserve existing signing/account data.
3. Download client configuration to private local storage and use the explicit
   `cmuxFirebaseConfig` build input. Do not commit live client files to this
   public repository; use the optional CI secret path when a configured release
   milestone is ready.
4. Configure a limited FCM provider identity for the private Mac helper. Keep
   provider credentials outside APKs/repository/logs. No billing upgrade is
   needed for the current project-creation step.
5. Complete helper enrollment/TLS/native producer assembly and real Mac/Pixel
   delivery acceptance, including process-dead/Doze, token rotation and revocation.

References: [project management API workflow](https://firebase.google.com/docs/projects/api/workflow_set-up-and-manage-project),
[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
