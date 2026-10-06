/* Local adapter. No document script, embedded HTML, network resources or active content. */
'use strict';
(async () => {
  try {
    const response = await fetch('document.zip', {credentials: 'omit', cache: 'no-store'});
    if (!response.ok) throw new Error('Document unavailable');
    const documentFile = await response.arrayBuffer();
    const rendered = await docx.renderAsync(documentFile, document.getElementById('content'),
      document.getElementById('styles'), {
        inWrapper: true, breakPages: true, ignoreLastRenderedPageBreak: false,
        renderHeaders: true, renderFooters: true, renderFootnotes: true, renderEndnotes: true,
        renderChanges: false, renderComments: false, renderAltChunks: false,
        ignoreFonts: false, useBase64URL: false, experimental: false, debug: false
      });
    if (!rendered.documentPart || !document.querySelector('section.docx')) throw new Error('Invalid document');
    // Keep page geometry; initially fit the widest page, with native pinch zoom available.
    const width = Math.ceil(document.getElementById('content').scrollWidth);
    document.querySelector('meta[name="viewport"]').content = 'width=' + Math.max(320, width);
    // Only visible, activated links may leave the local viewer. WebView checks user gesture too.
    document.addEventListener('click', event => {
      const link = event.target.closest && event.target.closest('a');
      if (!link) return;
      const href = link.getAttribute('href') || '';
      if (href.startsWith('#')) return;
      let scheme = '';
      try { scheme = new URL(href).protocol; } catch (_) {}
      if (!['https:', 'http:', 'mailto:', 'tel:'].includes(scheme)) event.preventDefault();
    }, true);
    window.__cmuxDocxReady = true;
    CmuxMarkdownBridge.postMessage(JSON.stringify({action: 'officeReady'}));
  } catch (_) {
    // Document text/paths never enter logs or diagnostic errors.
    document.getElementById('content').replaceChildren();
    CmuxMarkdownBridge.postMessage(JSON.stringify({action: 'officeFailed'}));
  }
})();
