# Original native Markdown leaf reference

The host compiles five whole unchanged parser/model files plus the entire
unchanged `ChatArtifactMarkdownNativeView` declaration from source revision
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`. The declaration is extracted to EOF
and receives authored Foundation/SwiftUI imports; the source web wrapper is
excluded. Original file, extracted declaration, compiled leaf, host, fixture,
test and preparer hashes are recorded and checked.

The isolated base-window host intercepts URL opening. It creates no account,
transport or production session. Synthetic Markdown covers all eight original
block kinds, inline styles, image alt text, nested lists, multilingual prose,
wide verbatim code and a table. It is rendered by the original SwiftUI view.
The independent UI-test process records actual `XCUIScreen` compositor pixels,
bounded public accessibility target frames and actual traits for 13 profiles:
nine dark locales, English light/landscape/tablet, German accessibility medium.
Each profile captures the top, code and end (39 images).

The UIKit preferred-style metrics are reference system fonts; they are not an
inspection of every SwiftUI glyph run. The palette is a standalone **base**
window palette, not the elevated original Feed sheet. The surrounding host is
authored and represents neither original Feed chrome nor production URL routing.
Selection gestures, VoiceOver, long-document restoration, transport, physical
iOS/Android and full app parity are outside this bounded capture.

Run `.github/workflows/ios-native-markdown.yml` manually. The exporter rejects
missing/reordered profiles/stages, dirty or mixed source, changed images,
incorrect actual traits, offscreen targets and mismatched compositor dimensions.
Synthetic export tests validate these integrity guards only; they are not
native UI evidence. No rendered reference is accepted until the actual workflow
and export association succeed and the resulting images are visually reviewed.
