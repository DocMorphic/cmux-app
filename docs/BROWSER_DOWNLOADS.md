# Browser download acceptance

## Source contract rechecked on 2026-10-02

At audited candidate `204a11dfcc76280205e50406ab94270a1c152155`, a streamed
browser's download is owned by the Mac:

- [BrowserNavigationDelegate](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/Panels/BrowserNavigationDelegate.swift)
  handles the HTML download action and response download policies, then assigns
  the Mac's download delegate to the resulting `WKDownload`.
- [BrowserDownloadDelegate in BrowserPanel.swift](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/Panels/BrowserPanel.swift)
  writes a temporary download, then either moves it to Mac Downloads with a
  collision-safe name or presents `NSSavePanel`, according to the existing Mac
  preference. Cancelling the save panel removes the temporary file.
- The reviewed [iOS streamed-browser action interface](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileBrowserStream/Sources/CmuxMobileBrowserStream/BrowserStreamSurfaceActions.swift)
  has navigation, pointer, key, text, viewport and browser-dialog actions. It has
  no action for transferring a download to the phone or answering a Mac save panel.
- The separate [phone-local WKWebView wrapper](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/MobileBrowserView.swift)
  has no `WKDownloadDelegate` or download destination implementation. This narrow
  source finding does not establish every OS-provided local-browser behavior.

Android's streamed browser already sends those actions to its Mac. These source
findings do **not** establish that a download initiated from Android has passed
live acceptance, or that an Android phone-download feature is implemented. The
live streamed-browser gate below remains open; no platform limitation is inferred.
Neither the broad implemented reference nor the audited candidate was advanced.

## Reproducible Pixel/Mac check

Use a supported paired Mac and a dedicated test browser workspace. The generated
fixture binds only `127.0.0.1` on this Mac, serves two generated files and its
landing page, and exposes no filesystem or terminal access. Each run has unique
file names and bytes; a new evidence directory is required.

```sh
python3 scripts/browser-download-fixture.py serve \
  --output captures/runtime/browser-downloads/physical-run-1
```

1. Record the installed Android APK hash, cmux Mac build and current Mac download
   preference. Open the printed URL in the phone's **Streamed** browser for this
   Mac. A phone-direct `127.0.0.1` page would address the phone instead.
2. With automatic saving selected on the Mac, tap **Download text** and
   **Download binary** once each. The first exercises an HTML `download` link;
   the second uses an attachment response. Record the phone's visible behavior,
   any Mac download status and the actual saved paths. A request in `receipt.json`
   alone is not proof of a saved download.
3. Verify each actual saved file, using its full path:

   ```sh
   python3 scripts/browser-download-fixture.py verify \
     --receipt captures/runtime/browser-downloads/physical-run-1/receipt.json \
     --asset text --file /actual/path/to/generated-file.txt
   python3 scripts/browser-download-fixture.py verify \
     --receipt captures/runtime/browser-downloads/physical-run-1/receipt.json \
     --asset binary --file /actual/path/to/generated-file.bin
   ```

4. Tap the binary link again. Verify the original file is unchanged and a distinct
   collision-resolved file has the same bytes. Record both actual names.
5. With **ask where to save** selected, repeat once and cancel the Mac save panel.
   Record that no final file was saved. Repeat and accept a path in a dedicated
   test folder; verify those bytes. Record whether the phone displays or can
   operate the prompt. Do not infer that its browser JavaScript-dialog UI can
   operate a native Mac save panel.
6. Restore the Mac's original preference, stop the fixture with Ctrl-C, and remove
   only this run's generated downloaded files after retaining verification results.
   Keep the receipt, actual APK/build identities, screenshots and results under
   the ignored evidence directory. Do not replace a failed live check with the
   local fixture check below.

The verifier reads the supplied file and compares its size and SHA-256. A mismatch
exits unsuccessfully. HEAD requests do not increment completed-response counts.
Response counts cannot establish which device saved a file, save-panel completion,
file integrity, or successful browser navigation.

## Fixture verification — not live acceptance

The local Python HTTP probe verified the landing page's unique suggested name,
both HEAD/GET routes, content types and attachment disposition, exact expected
hashes, successful verification of both saved probe files, rejection of mismatched
bytes, rejection of unknown paths and exclusion of HEAD from response counts.
Evidence: ignored `captures/runtime/browser-download-audit/local-probe/`.
The loopback server was stopped. No cmux setting, personal file, Android package
or upstream pin was changed, and no emulator was started for this check.

No Pixel was connected at this checkpoint. Physical streamed downloads, the Mac
save preference paths and phone-local download behavior remain unverified.
