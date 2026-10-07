# Explicit Firebase Android configuration

This connects a deliberately selected Firebase Android client to an APK. It does
not provision a Firebase project, deploy a notification helper, enroll a phone,
or establish successful delivery. The Mac source adapter and production setup
remain required work in [PUSH_DELIVERY.md](PUSH_DELIVERY.md).

## Select the client configuration

Register Android apps in the intended Firebase project for these exact packages:

- Debug: `io.github.docmorphic.cmuxapp.debug`
- Release: `io.github.docmorphic.cmuxapp`

Download the Android `google-services.json` configuration from that project.
This is an Android client configuration, **not** a service-account credential.
Keep it outside the repository. No current `gcloud` project or other account is
automatically selected. Files named `google-services.json` are ignored by Git.

Supply its absolute path explicitly:

```sh
./gradlew -PcmuxFirebaseConfig=/absolute/path/google-services.json :app:assembleDebug
```

Or set `CMUX_FIREBASE_CONFIG` to that path. The Gradle property takes precedence
over the environment variable. Without either input, no Google Services resource
processing is applied and the push settings show an unconfigured service. Merely
placing a JSON file under `app/` does not opt in.

Google's official Google Services plugin **4.5.0** selects the client for the
variant's actual application ID. A missing file or missing matching client fails
the requested build. When building debug and release together, the supplied JSON
must contain clients for both packages in its `client` array. For separate
configuration files, build each variant separately with its corresponding file.
Do not use the repository's synthetic test fixture for a phone or release build.

The plugin creates the default Firebase SDK configuration. Messaging auto-init,
notification delegation, Analytics and default data collection remain disabled
in the manifest. Merely initializing the configured SDK does not create app consent
or an enrolled helper. The existing Settings consent/account/permission checks
still gate explicit token acquisition and helper pairing. Those steps require a
real provisioned project and the completed helper setup.

## GitHub milestone builds

The Android build workflow accepts an optional repository secret named
`CMUX_FIREBASE_CONFIG_JSON` containing the Android client JSON for both variants.
If supplied, it writes a private temporary file and passes only its path to Gradle.
The workflow does not print the JSON or publish it as a separate artifact. Client
configuration is necessarily embedded in the APK, as in ordinary Firebase apps;
server credentials must never be supplied here. No secret or project was configured
by this code change. Scheduled preview builds remain opt-in as before.

## Focused verification — 2026-10-07

The synthetic fixture in `scripts/tests/fixtures/firebase-clients.json` selects
separate debug and release app IDs. Both generated resource sets were checked.
Missing explicit files and debug builds given only a release client fail for the
expected reasons. The workflow preparation was exercised with absent input,
valid client input and a rejected service-account-shaped input; client files are
mode `0600` and their contents are not printed.

`PhoneFirebaseConfigurationTest` runs only with an explicit
`firebase_configuration_fixture` instrumentation argument (`configured` or
`unconfigured`) and checks emulator hardware. It never requests a provider token.
Use the synthetic configuration only on the emulator. Ordinary test suites skip
this fixture; that skip is not initialization evidence.

Build the configured debug and test APKs with the explicit fixture path, install
them on the existing emulator and run:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e firebase_configuration_fixture configured \
  -e class io.github.docmorphic.cmuxapp.PhoneFirebaseConfigurationTest \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

For the removal check, rebuild both APKs without the property or environment
variable, replace both installed APKs, and run with `unconfigured`. This checks
that generated resources from the previous build do not initialize the SDK after
configuration is removed; deleting the build directory first would weaken that
check.

Both modes passed on the existing API37 arm64 emulator with 16 KiB pages:
**one configured case (0.185 s)** and **one unconfigured case (0.018 s)**, no
skips. The checks assert all four manifest opt-outs, expected default SDK state,
setup availability and absent enrollment/token. The configured boot had empty
crash/ANR logs. The second boot recorded a Google Play services persistent-process
ANR for `SIM_STATE_CHANGED` before instrumentation; the before/after event lists
were identical, with no new test-time events and an empty crash buffer. This does
not establish provider health or live delivery.

Debug and instrumentation APK builds passed (7m 8s for the initial plugin change,
1m 3s for the unconfigured rebuild). No clean rebuild was used between modes.
The current local APK is unconfigured. Emulator and Gradle were stopped; no new
AVD, physical-device install or signed release was produced.

Evidence is in ignored `captures/runtime/firebase-config/`, including build logs,
generated fixture resources, expected failures, instrumentation results, APK
hashes and `verification.json`. This establishes SDK configuration and removal;
real consent/token operations, worker restart, helper delivery and Doze remain
open.

## References

- [Firebase Android setup](https://firebase.google.com/docs/android/setup)
- [Google Services configuration processing](https://firebase.google.com/docs/android/google-services-plugin-and-file)
