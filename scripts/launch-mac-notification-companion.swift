#!/usr/bin/env swift
import AppKit
import Foundation

// Launch a verified prebuilt tagged bundle without touching the official running app.
// The full app build and archive verification are performed by the documented workflow.
let identity = "com.cmuxterm.app.debug.android.push"
let arguments = Array(CommandLine.arguments.dropFirst())
guard arguments.count == 4, arguments.allSatisfy({ $0.hasPrefix("/") && !$0.contains("\0") }),
      let bundle = Bundle(url: URL(fileURLWithPath: arguments[0])), bundle.bundleIdentifier == identity,
      let declared = bundle.infoDictionary?["LSEnvironment"] as? [String: String],
      declared["CMUX_AUTH_ENVIRONMENT"] == "production",
      declared["CMUX_API_BASE_URL"] == "https://cmux.com",
      Set(declared.keys).isDisjoint(with: ["CMUX_AUTH_CREDENTIALS_FILE", "CMUX_DEV_AUTH_PROFILE",
          "CMUX_DEV_AUTH_REPLACE_SESSION", "CMUX_ANDROID_PUSH_CONFIG", "CMUX_ANDROID_PUSH_NODE", "CMUX_ANDROID_PUSH_HELPER"]),
      arguments.dropFirst().allSatisfy({ FileManager.default.fileExists(atPath: $0) }) else {
    fputs("Select the verified tagged app and explicit private config/Node/helper paths.\n", stderr)
    exit(2)
}
let running = NSRunningApplication.runningApplications(withBundleIdentifier: identity)
guard running.isEmpty else {
    fputs("The tagged app is already running; reuse it before requesting another launch.\n", stderr)
    exit(2)
}
// GUI terminals receive ordinary user environment, not the agent's API credentials.
let ordinaryKeys: Set<String> = ["HOME", "PATH", "USER", "LOGNAME", "SHELL", "TMPDIR",
    "LANG", "LC_CTYPE", "SSH_AUTH_SOCK", "__CF_USER_TEXT_ENCODING"]
var environment = ProcessInfo.processInfo.environment.filter { ordinaryKeys.contains($0.key) }
for (key, value) in declared { environment[key] = value }
environment["CMUX_ANDROID_PUSH_CONFIG"] = arguments[1]
environment["CMUX_ANDROID_PUSH_NODE"] = arguments[2]
environment["CMUX_ANDROID_PUSH_HELPER"] = arguments[3]
// The pinned DEBUG resolver documents that an unknown explicit profile admits
// no auto-login account. This avoids reading ~/.secrets or inheriting test creds;
// the separate bundle signs in through its ordinary production account UI.
environment["CMUX_DEV_AUTH_PROFILE"] = "cmux-app-interactive"
let configuration = NSWorkspace.OpenConfiguration()
configuration.environment = environment
configuration.activates = true
NSWorkspace.shared.openApplication(at: bundle.bundleURL, configuration: configuration) { app, error in
    guard error == nil, app?.bundleIdentifier == identity else {
        fputs("Tagged app launch failed.\n", stderr)
        exit(1)
    }
    print("Isolated tagged app launched; official app was not stopped or replaced")
    exit(0)
}
RunLoop.main.run()
