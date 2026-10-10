import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
const require=createRequire(import.meta.url);
const {JSDOM}=createRequire(new URL('../viewer-tests/package.json',import.meta.url))('jsdom');
const model=require('../../app/src/main/assets/rtf-viewer/rtf-model.js');
const folder=new URL('../../app/src/main/assets/rtf-viewer/',import.meta.url);
function browser() {
  const dom=new JSDOM('<!doctype html><main id="content"></main>',{runScripts:'outside-only',url:'https://offline.invalid/'});
  for (const name of ['WMFJS','EMFJS']) {
    dom.window.eval(readFileSync(new URL(name+'.bundle.js',folder),'utf8'));
    dom.window[name].loggingEnabled(false);
  }
  dom.window.eval(readFileSync(new URL('rtf-model.js',folder),'utf8'));
  dom.window.eval(readFileSync(new URL('RTFJS.bundle.js',folder),'utf8'));
  dom.window.RTFJS.loggingEnabled(false);
  return dom;
}
function buffer(bytes) { return bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength); }
test('actual pinned renderer preserves authored Unicode, formatting, links, and raster/vector pictures',async()=>{
  const dom=browser();
  try {
    const bytes=readFileSync(new URL('../../app/src/androidTest/assets/rtf/rich.rtf',import.meta.url));
    // RTFJS expects its own realm's ArrayBuffer for binary pictures.
    const input=new dom.window.Uint8Array(bytes).buffer;
    const content=await model.render(input,dom.window.RTFJS,dom.window.document);
    assert.match(content.textContent,/RTF preview fixture/);
    assert.match(content.textContent,/Résumé 日本語/);
    assert.match(content.textContent,/Literal <script> text and escaped \{braces\}/);
    assert.match(content.textContent,/Paragraph 80/);assert.match(content.textContent,/RTF_FINAL_MARKER/);
    const spans=Array.from(content.querySelectorAll('span'));
    assert.ok(spans.some(n=>n.textContent==='Bold' && n.style.fontWeight==='bold'));
    assert.ok(spans.some(n=>n.textContent==='italic' && n.style.fontStyle==='italic'));
    assert.ok(spans.some(n=>n.textContent==='red text' && n.style.color==='rgb(180, 20, 40)'));
    assert.equal(content.querySelectorAll('a').length,1);
    assert.equal(content.querySelector('a').href,'https://example.invalid/rtf');
    assert.equal(content.querySelectorAll('img').length,1);
    assert.match(content.querySelector('img').src,/^data:image\/png;base64,/);
    assert.equal(Array.from(content.querySelectorAll('svg')).filter(n=>!n.parentElement.closest('svg')).length,2);
    assert.ok(content.querySelectorAll('svg rect').length>=2);
    assert.equal(content.querySelectorAll('script,iframe,object').length,0);
    assert.ok(!Array.from(content.querySelectorAll('[href],[src]')).some(n=>/javascript:|private\.png/.test(n.getAttribute('href')||n.getAttribute('src'))));
  } finally { dom.window.close(); }
});
test('safe links accept supported absolute schemes and reject active, relative or file URLs',()=>{
  for (const value of ['javascript:alert(1)','data:text/html,x','file:///private','../document','blob:x','https:'+'x'.repeat(8193)]) assert.equal(model.external(value),null);
  for (const value of ['https://example.invalid/','http://example.invalid/','mailto:reader@example.invalid','tel:+12345']) assert.ok(model.external(value));
});
test('generated DOM sanitization retires active elements, unsafe image URLs, handlers and external SVG/style resources',()=>{
  const dom=new JSDOM('<main></main>');
  try {
    const container=dom.window.document.querySelector('main');
    container.innerHTML='<div onclick="bad()"><script>bad()</script><iframe src="https://example.invalid/"></iframe><span style="background-image:url(https://example.invalid/private)">literal</span><a href="javascript:bad()">inert</a><img src="https://example.invalid/private"><svg><image href="https://example.invalid/private"/><rect fill="url(https://example.invalid/fill)"/><use href="#local"/><path clip-path="url(#local)"/></svg></div>';
    model.clean(container);
    assert.equal(container.querySelectorAll('script,iframe,[onclick],[src]').length,0);
    assert.equal(container.querySelector('a').getAttribute('href'),null);
    assert.equal(container.querySelector('image').getAttribute('href'),null);
    assert.equal(container.querySelector('rect').getAttribute('fill'),null);
    assert.equal(container.querySelector('span').style.backgroundImage,'');
    assert.equal(container.querySelector('use').getAttribute('href'),'#local');
    assert.equal(container.querySelector('path').getAttribute('clip-path'),'url(#local)');
    assert.match(container.textContent,/literal/);
  } finally { dom.window.close(); }
});
test('renderer external imports receive an error without starting a fetch',async()=>{
  const dom=new JSDOM('<main></main>');
  try {
    let imported=false;
    class FakeDocument {
      constructor(_bytes,options) {
        options.onImport('https://example.invalid/secret',result=>{imported=true;assert.ok(result.error);assert.equal(result.blob,undefined)});
      }
      async render() { return [dom.window.document.createElement('span')]; }
    }
    await model.render(buffer(Buffer.from('fixture')),{Document:FakeDocument},dom.window.document);
    assert.ok(imported);
  } finally { dom.window.close(); }
});
test('bounded generated DOM rejects oversized element trees before mounting',()=>{
  let calls=0;
  const text={nodeType:3};
  const root={childNodes:Array.from({length:100001},()=>{calls++;return text})};
  assert.throws(()=>model.clean(root),/budget/);
  assert.equal(calls,100001);
});
function wmf(records) {
  const header=Buffer.alloc(18);header.writeUInt16LE(1,0);header.writeUInt16LE(9,2);
  return buffer(Buffer.concat([header,...records]));
}
function record(type=0x020b,size=10) {
  const result=Buffer.alloc(size);result.writeUInt32LE(size/2,0);result.writeUInt16LE(type,4);return result;
}
test('vector budgets reject truncated/zero records before entering vendor decoders',()=>{
  assert.throws(()=>model.metafile(new ArrayBuffer(3),'wmf'),/Truncated/);
  const malformed=record();malformed.writeUInt32LE(0,0);
  assert.throws(()=>model.metafile(wmf([malformed]),'wmf'),/Invalid vector record/);
  const truncated=record();truncated.writeUInt32LE(5000,0);
  assert.throws(()=>model.metafile(wmf([truncated]),'wmf'),/Truncated/);
  assert.throws(()=>model.metafile(new ArrayBuffer(8*1024*1024+1),'emf'),/budget/);
});
test('vector record count is capped before constructing a potentially enormous SVG tree',()=>{
  const records=Array.from({length:8192},()=>record());records.push(record(0,6));
  assert.throws(()=>model.metafile(wmf(records),'wmf'),/record budget/);
  model.metafile(wmf([record(),record(0,6)]),'wmf');
});
test('wrapped vendor API cannot start decoding a rejected metafile',()=>{
  let starts=0;
  class Original {constructor(){starts++;}}
  const api=model.pictureAPI({Renderer:Original,flag:true},'wmf');
  assert.equal(api.flag,true);
  assert.throws(()=>new api.Renderer(new ArrayBuffer(3)),/Truncated/);
  assert.equal(starts,0);
  new api.Renderer(wmf([record(0,6)]));assert.equal(starts,1);
});
