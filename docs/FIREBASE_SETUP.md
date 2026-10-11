# Dedicated Firebase setup — 2026-10-11

The user explicitly selected private Firebase plus a custom cmux Mac extension,
then authorized creating a dedicated project.

## Current state — corrected account

The user clarified that the intended Google account is **davedharmay@gmail.com**.
The initial project was mistakenly created using the Mac’s existing gcloud account
in another Google Cloud organization. That empty project
`cmux-app-push-20261011` has been shut down; `lifecycleState: DELETE_REQUESTED`
was independently verified. No Firebase apps, client configuration, service-account
keys, billing linkage or live sender were created in it. Do not use it for cmux-app.

A new `gcloud auth login davedharmay@gmail.com` browser authorization is pending.
Finish it with the intended account; do not reuse the other cached account.
Before any replacement provisioning, verify the authenticated account and pass
`--account=davedharmay@gmail.com` and the replacement project ID explicitly.
The previously requested terms step under the wrong account is superseded.

The wrong-account project’s Firebase Management and FCM APIs were enabled and
billing was verified disabled before cleanup. Adding Firebase had returned HTTP
403 despite Owner access. That result is not evidence about the intended Gmail
account and must not be used to diagnose its setup.

For the replacement, create a dedicated project (a different ID is required while
the old ID remains reserved), add Firebase, verify the completed FirebaseProject
operation, and keep Analytics/billing off. Record its verified resource metadata
here. The Firebase [existing-project guide](https://firebase.google.com/docs/projects/use-firebase-with-existing-cloud-project)
documents the separate account terms/permission requirements if activation fails.

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
