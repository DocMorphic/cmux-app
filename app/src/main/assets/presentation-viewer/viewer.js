import {PptxViewer, parseZip, buildPresentation} from './aiden0z-pptx-renderer.browser.es.js';

const content = document.getElementById('content');
const status = document.getElementById('status');
const slide = document.getElementById('slide');
const previous = document.getElementById('previous'), next = document.getElementById('next'), go = document.getElementById('go');
const post = message => CmuxMarkdownBridge.postMessage(JSON.stringify(message));
let viewer, busy = false, ready = false, incomplete = false;
function controls() {
  previous.disabled = busy || !ready || viewer.currentSlideIndex <= 0;
  next.disabled = busy || !ready || viewer.currentSlideIndex >= viewer.slideCount - 1;
  go.disabled = busy || !ready;
  slide.disabled = busy || !ready;
}
function changed(index) {
  slide.value = index + 1;
  post({action: 'presentationState', slide: index});
  controls();
}
async function navigate(index) {
  if (busy || !ready) return;
  if (!Number.isInteger(index) || index < 0 || index >= viewer.slideCount) {
    status.textContent = `Enter a slide number from 1 to ${viewer.slideCount}.`; return;
  }
  busy = true; incomplete = false; controls();
  try {
    await viewer.goToSlide(index);
    window.scrollTo(0, 0);
    status.textContent = incomplete ? 'Some slide content could not be displayed. Use Viewer actions to open the original.' : '';
  } catch (_) { status.textContent = 'This slide could not be displayed. Try another slide or open the original.'; }
  finally { busy = false; controls(); }
}
previous.addEventListener('click', () => navigate(viewer.currentSlideIndex - 1));
next.addEventListener('click', () => navigate(viewer.currentSlideIndex + 1));
go.addEventListener('click', () => navigate(Number(slide.value) - 1));
slide.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); navigate(Number(slide.value) - 1); } });
window.addEventListener('pagehide', () => viewer?.destroy(), {once: true});
try {
  const response = await fetch('document.zip', {credentials: 'omit', cache: 'no-store'});
  if (!response.ok) throw new Error('Document unavailable');
  const files = await parseZip(await response.arrayBuffer(), {
    maxEntries: 2048, maxEntryUncompressedBytes: 16 * 1024 * 1024,
    maxTotalUncompressedBytes: 64 * 1024 * 1024, maxMediaBytes: 48 * 1024 * 1024, maxConcurrency: 2,
  });
  const presentation = buildPresentation(files, {lazySlides: true});
  if (!presentation.slides.length || presentation.slides.length > 2048) throw new Error('Slide count unavailable');
  viewer = new PptxViewer(content, {fitMode: 'contain', pdfjs: false,
    onSlideChange: changed,
    onNodeError: () => { incomplete = true; }, onSlideError: () => { incomplete = true; },
  });
  viewer.load(presentation);
  const initial = Number(new URLSearchParams(location.hash.slice(1)).get('slide'));
  await viewer.renderSlide(Number.isInteger(initial) ? Math.min(viewer.slideCount - 1, Math.max(0, initial)) : 0);
  slide.max = viewer.slideCount; document.getElementById('count').textContent = `of ${viewer.slideCount}`;
  ready = true; controls();
  status.textContent = incomplete ? 'Some slide content could not be displayed. Use Viewer actions to open the original.' : '';
  window.__cmuxPresentationReady = true;
  post({action: 'officeReady'});
} catch (_) {
  viewer?.destroy(); content.replaceChildren(); post({action: 'officeFailed'});
}
