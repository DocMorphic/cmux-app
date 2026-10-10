/* Offline renderer adapter. Document fields cannot import files or active URLs. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else {
    root.CmuxRtfModel = factory();
    // Wrap vector decoders before RTFJS captures these module references.
    root.WMFJS = root.CmuxRtfModel.pictureAPI(root.WMFJS, 'wmf');
    root.EMFJS = root.CmuxRtfModel.pictureAPI(root.EMFJS, 'emf');
  }
})(globalThis, function() {
  'use strict';
  const tags = new Set(['div','span','a','img','svg','g','path','rect','circle','ellipse','line','polyline','polygon',
    'text','tspan','defs','clippath','pattern','image','use','filter','feflood','fecomposite','femerge','femergenode']);
  function external(raw) {
    if (typeof raw !== 'string' || raw.length > 8192) return null;
    try { const url = new URL(raw); return ['http:','https:','mailto:','tel:'].includes(url.protocol) ? url.href : null; }
    catch (_) { return null; }
  }
  const image = raw => typeof raw === 'string' && raw.length <= 24 * 1024 * 1024 &&
    /^data:image\/(png|jpeg|bmp);base64,[a-z0-9+/]*={0,2}$/i.test(raw);
  const local = raw => /^#[a-z_][a-z0-9_.:-]*$/i.test(raw || '');
  function metafile(buffer, kind) {
    if (!buffer || buffer.byteLength > 8*1024*1024) throw new Error('Vector picture exceeds preview budget');
    const data=new DataView(buffer); let offset=0,count=0;
    const requireBytes=(at,size)=>{if(at<0 || size<0 || at+size>data.byteLength) throw new Error('Truncated vector picture');};
    const u32=at=>{requireBytes(at,4);return data.getUint32(at,true);};
    const u16=at=>{requireBytes(at,2);return data.getUint16(at,true);};
    if(kind==='wmf') {
      if(u32(0)===0x9ac6cdd7) offset=22;
      requireBytes(offset,18);
      if(![1,2].includes(u16(offset)) || u16(offset+2)!==9) throw new Error('Invalid WMF header');
      offset+=18;
    } else if(kind==='emf') {
      requireBytes(0,88);
      if(u32(0)!==1 || u32(4)<88 || u32(40)!==0x464d4520) throw new Error('Invalid EMF header');
    } else throw new Error('Unsupported vector picture');
    for(;;) {
      if(++count>8192) throw new Error('Vector picture record budget exceeded');
      const size=kind==='wmf' ? u32(offset)*2 : u32(offset+4);
      const type=kind==='wmf' ? u16(offset+4) : u32(offset);
      if(size<(kind==='wmf'?6:8) || (kind==='emf' && size%4)) throw new Error('Invalid vector record');
      requireBytes(offset,size);
      if(type===(kind==='wmf'?0:14)) return;
      offset+=size;
    }
  }
  function pictureAPI(api, kind) {
    const Original=api.Renderer;
    return {...api,Renderer:class extends Original {
      constructor(buffer,...args) { metafile(buffer,kind); super(buffer,...args); }
    }};
  }
  function clean(root) {
    let count = 0;
    const visit = node => {
      if (++count > 100_000) throw new Error('Rich text render budget exceeded');
      if (node.nodeType === 3) return;
      if (node.nodeType !== 1 || !tags.has(node.localName.toLowerCase())) { node.remove(); return; }
      for (const attr of Array.from(node.attributes)) {
        if (/^on/i.test(attr.name) || ['srcdoc','target','download','formaction'].includes(attr.name.toLowerCase())) node.removeAttribute(attr.name);
      }
      for (const attr of ['href','xlink:href','src']) {
        const raw = node.getAttribute(attr);
        if (raw == null) continue;
        const valid = node.localName === 'a' && attr === 'href' ? external(raw) :
          attr === 'src' || node.localName === 'image' ? image(raw) : local(raw);
        if (!valid) node.removeAttribute(attr);
      }
      for (const attr of ['fill','stroke','filter','clip-path','mask']) {
        const raw = node.getAttribute(attr);
        if (/url\(/i.test(raw || '') && !/^url\(#[a-z_][a-z0-9_.:-]*\)$/i.test(raw)) node.removeAttribute(attr);
      }
      for (const property of Array.from(node.style || [])) {
        const raw = node.style.getPropertyValue(property);
        if (/url\(|expression\(|@import/i.test(raw) && !/^url\(#[a-z_][a-z0-9_.:-]*\)$/i.test(raw)) node.style.removeProperty(property);
      }
      for (const child of Array.from(node.childNodes)) visit(child);
    };
    for (const child of Array.from(root.childNodes)) visit(child);
    return root;
  }
  async function render(buffer, api, document) {
    const doc = new api.Document(buffer, {
      onHyperlink: (_create, link) => {
        const href = external(link.url()), element = document.createElement(href ? 'a' : 'span');
        if (href) element.setAttribute('href', href);
        return {element, content: element};
      },
      onImport: (_url, callback) => callback({error: new Error('External document resources are unavailable')}),
    });
    const elements = await doc.render(), container = document.createElement('div');
    // Append to an unattached container, then check generated DOM before mounting.
    for (const element of elements) container.appendChild(element);
    return clean(container);
  }
  return {external,image,metafile,pictureAPI,clean,render};
});
