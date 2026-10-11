# Dedicated Firebase setup — 2026-10-11

The user selected private Firebase plus a custom cmux Mac extension and authorized
a dedicated project. The intended Google account is **davedharmay@gmail.com**.

## Verified current state

- Project ID: `cmux-app-push-private-20261011`; display name `cmux-app`.
- Project number: `185554994782`; Cloud lifecycle `ACTIVE`, no organization parent.
- Gmail browser authorization completed; project Owner verified for that account.
  All provisioning calls used explicit account and project, ignoring gcloud’s
  unrelated current project.
- Firebase activation completed successfully (`done: true`, FirebaseProject
  response). Firebase Management, FCM and IAM APIs were enabled for this project.
- `billingEnabled: false` verified; no Analytics link was created.
- Android clients registered: `io.github.docmorphic.cmuxapp` and
  `io.github.docmorphic.cmuxapp.debug`.
- The two selected client records were checked against their registered package,
  Firebase app ID, project ID and sender/project number. Configuration is stored
  privately at `~/.config/cmux-app/firebase/cmux-app-push-private-20261011/google-services.json`
  (file mode 0600, containing directory 0700). It is absent from this repository.
- GitHub Actions secret `CMUX_FIREBASE_CONFIG_JSON` was set from that verified file;
  its existence/update timestamp was independently checked. This contains only
  Android client configuration, never provider/service-account private keys.
- Both `processDebugGoogleServices` and `processReleaseGoogleServices` completed
  successfully in five seconds. Generated XML independently matches each variant’s
  Firebase app ID, project ID and sender number. No APK was built in that check.
- Provider identity created:
  `cmux-android-push@cmux-app-push-private-20261011.iam.gserviceaccount.com`.
  Its `roles/firebasecloudmessaging.admin` project binding was independently
  verified. One enabled user-managed RSA key was subsequently provisioned and its
  key name independently matched against Google's key list.
- Provider credential is private at
  `~/.config/cmux-app/firebase/cmux-app-push-private-20261011/provider-service-account.json`
  (0600 file, 0700 containing directory), outside GitHub and Android configuration.
  `push/private-credentials.mjs` explicitly loads this owner-selected file, rejects
  nonprivate permissions, symlinks/hard links, nonfiles and oversized content, and
  binds it to the exact expected project/service account. It does not discover
  gcloud/environment credentials or contact Google until the provider's `get()`.
- A real OAuth exchange through that loader and the existing sender provider
  succeeded against Google's fixed token endpoint with the messaging scope.
  A second read used the cached token; one OAuth request, **zero FCM requests**.
  Private credentials/assertions/access-token values were not printed or committed.
  Fifteen focused loader/sender checks passed on Node 26.8.2 (119.511 ms) and
  22.16.0 (311.771 ms), using public test keys/injected OAuth transport.

Original resource/operation metadata, client registration results, resource build
log and independent XML comparison are in ignored `captures/runtime/firebase-setup/`.
Credentials/access tokens/client key values were not printed. The API returns
project-wide client records; the private combined file selects exactly one record
for each registered app rather than concatenating duplicate records.

## Corrected initial account mistake

The first empty Cloud project `cmux-app-push-20261011` was mistakenly created
using the Mac’s old gcloud account in another organization. It was shut down;
`DELETE_REQUESTED` was independently verified. No Firebase clients, provider keys,
billing link or live sender were created there. Do not use that project. Its
activation 403 is not evidence about the intended Gmail account; the replacement
activated successfully without another terms prompt.

## Next integration

1. Helper sender/storage keys are now provisioned privately and the CryptoKit
   sealer compiled. Complete trusted TLS/runtime configuration and apply/build the
   native integration. Full Xcode installation and Tailscale HTTPS Certificates
   are currently requested from the owner.
   Source injection/public-descriptor publication and standalone native owner
   execution are now checked; whole-app/native account acceptance remains open.
   The production entrypoint now composes the verified private provider; see
   PUSH_HELPER_RUNTIME.md for its explicit configuration and owner pairing control.
   The helper component lifecycle is assembled and locally checked; its provider
   transport is still injected in tests (see NATIVE_PUSH_SOURCE.md).
2. Build the configured Android/custom Mac integration milestone. Existing
   `cmuxFirebaseConfig` and `CMUX_FIREBASE_CONFIG` inputs use this private file;
   GitHub milestone run `38169223650` now targets source
   `d34daed53231997ffa68de63233357e6fe98d489` with that secret. It is in progress;
   exact APK resources/signature/alignment/ART checks still need terminal success.
   This run does not build or launch the full custom Mac app.
3. Verify actual consent/token registration, enrollment and real Mac/Pixel
   foreground/background/process-dead/Doze delivery, token rotation, tap/reply,
   dismissals and account revocation. Client registration/resource generation do
   not establish token acquisition or push delivery.

References: [project management workflow](https://firebase.google.com/docs/projects/api/workflow_set-up-and-manage-project),
[FCM HTTP v1 and provider role](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
