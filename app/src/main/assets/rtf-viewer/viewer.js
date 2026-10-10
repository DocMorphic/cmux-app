'use strict';
(async () => {
  const content = document.getElementById('content');
  try {
    RTFJS.loggingEnabled(false); WMFJS.loggingEnabled(false); EMFJS.loggingEnabled(false);
    const response = await fetch('document.rtf', {credentials:'omit',cache:'no-store'});
    if (!response.ok) throw new Error('Document unavailable');
    const rendered = await CmuxRtfModel.render(await response.arrayBuffer(), RTFJS, document);
    content.replaceChildren(...Array.from(rendered.childNodes));
    document.addEventListener('click', event => {
      const link = event.target.closest?.('a');
      if (link && !CmuxRtfModel.external(link.getAttribute('href'))) event.preventDefault();
    }, true);
    await Promise.all(Array.from(content.querySelectorAll('img')).map(img => img.complete ? Promise.resolve() :
      new Promise(resolve => { img.addEventListener('load',resolve,{once:true}); img.addEventListener('error',resolve,{once:true}); })));
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    window.__cmuxRtfReady = true;
    CmuxMarkdownBridge.postMessage(JSON.stringify({action:'officeReady'}));
  } catch (_) {
    // Document contents and local paths must not enter logs or errors.
    content.replaceChildren();
    CmuxMarkdownBridge.postMessage(JSON.stringify({action:'officeFailed'}));
  }
})();
