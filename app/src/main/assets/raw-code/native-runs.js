// This host adapter never renders user source as HTML. Only highlight.js's escaped
// output enters the local DOM, and its decoded text must equal the source.
window.cmuxNativeHighlight = function (source, language) {
  let result;
  try {
    result = language ? hljs.highlight(source, { language, ignoreIllegals: false }) : hljs.highlightAuto(source);
  } catch (_) {
    result = hljs.highlightAuto(source);
  }
  if (!result || typeof result.value !== 'string') return null;
  const root = document.getElementById('code');
  // HTML parsing would otherwise normalize CR/CRLF, changing native UTF-16 offsets.
  root.innerHTML = result.value.replace(/\r/g, '&#13;');
  if (root.textContent !== source) return null;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const runs = [];
  let node, offset = 0;
  while ((node = walker.nextNode())) {
    const length = node.data.length;
    if (!length) continue;
    const style = getComputedStyle(node.parentElement);
    const rgb = style.color.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*1)?\)$/);
    if (!rgb) return null;
    const color = (0xff000000 | (+rgb[1] << 16) | (+rgb[2] << 8) | +rgb[3]);
    const flags = (parseInt(style.fontWeight, 10) >= 600 ? 1 : 0) | (style.fontStyle === 'italic' ? 2 : 0);
    const previous = runs[runs.length - 1];
    if (previous && previous[2] === color && previous[3] === flags) previous[1] += length;
    else runs.push([offset, offset + length, color, flags]);
    offset += length;
  }
  return { text: source, language: result.language || language || null, runs };
};
