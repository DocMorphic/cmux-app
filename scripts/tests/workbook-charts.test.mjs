import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
const require=createRequire(import.meta.url);
const model=require('../../app/src/main/assets/workbook-viewer/workbook-model.js');
const drawings=require('../../app/src/main/assets/workbook-viewer/workbook-drawings.js');
const charts=require('../../app/src/main/assets/workbook-viewer/workbook-charts.js');
const {JSDOM}=createRequire(new URL('../viewer-tests/package.json',import.meta.url))('jsdom');
const dom=new JSDOM('');
globalThis.DOMParser=dom.window.DOMParser;
const api=await import('../../app/src/main/assets/presentation-viewer/aiden0z-pptx-renderer.browser.es.js');
const parse=source=>new dom.window.DOMParser().parseFromString(source,'application/xml');
const serialize=document=>new dom.window.XMLSerializer().serializeToString(document);
const book=()=>model.read(readFileSync(new URL('../../app/src/androidTest/assets/workbook/charts.xlsx',import.meta.url)));
const xmlFor=book=>path=>book.files[path]?.content && parse(new TextDecoder().decode(book.files[path].content));
test('actual worksheet anchors resolve all authored chart families without reading picture bytes',()=>{
  const b=book(),xml=xmlFor(b),result=drawings.objects('xl/worksheets/sheet1.xml',xml,()=>assert.fail('Charts are not image bytes'));
  assert.equal(result.unsupported,0);assert.equal(result.images.length,0);assert.equal(result.charts.length,8);
  assert.equal(result.charts[0].alt,'Column chart');assert.equal(result.charts[0].from.row,3);
  assert.equal(result.charts[6].from.row,130);assert.ok(result.charts[0].width>400);
  assert.deepEqual(result.charts.map(chart=>chart.zOrder),[0,1,2,3,4,5,6,7]);
});
test('uncached local worksheet data reaches the real pinned chart parser with literal labels and series values',()=>{
  const b=book(),xml=xmlFor(b),objects=drawings.objects('xl/worksheets/sheet1.xml',xml,()=>null);
  for(const descriptor of objects.charts) {
    const projected=charts.prepare(descriptor,b,xml,serialize);
    const source=charts.presentation(projected,400,240,api);
    api.materializeAllSlideNodes(source);
    assert.equal(source.slides.length,1);assert.equal(source.slides[0].nodes.length,1,descriptor.alt);
    assert.equal(source.charts.size,1);assert.equal(projected.incomplete,false);
    assert.match(projected.maps.charts.get(descriptor.path),/100/);
  }
  const projected=charts.prepare(objects.charts[0],b,xml,serialize);
  assert.match(projected.maps.charts.get(projected.path),/Q1 中/);
  assert.match(projected.maps.charts.get(projected.path),/strRef/);
  assert.match(projected.maps.charts.get(projected.path),/175/);
  assert.doesNotMatch(serialize(xml(projected.path)),/numCache|strCache/,'Owned source XML remains untouched');
});
test('chart-only sheets become navigable through the shared sparse anchor geometry',()=>{
  const b=book(),xml=xmlFor(b),objects=drawings.objects('xl/worksheets/sheet3.xml',xml,()=>null);
  assert.equal(objects.charts.length,1);drawings.includeBounds(b.Sheets['Chart only'],objects.charts);
  assert.equal(model.windowFor(b,2).empty,false);
  assert.ok(model.range(b.Sheets['Chart only']['!ref']).e.r>11);
});
test('reference hydration admits only bounded local rows or columns and preserves quoted sheet names',()=>{
  const b=book();assert.equal(charts.reference("'Data''s résumé'!$B$2:$B$4",b).length,3);
  assert.deepEqual(charts.reference("'Data''s résumé'!B2:C2",b).map(cell=>cell.v),[100,80]);
  for(const bad of ["'[outside.xlsx]Data'!B2:B4","'Data''s résumé'!B2:C4","'Data''s résumé'!XFE1","'Data''s résumé'!A1048577",'https://bad.invalid/data!A1','Missing!A1',"'Data''s résumé'!B4:B2"]) assert.equal(charts.reference(bad,b),null,bad);
  assert.throws(()=>charts.reference("'Data''s résumé'!B1:B10001",b),/budget/);
});
test('declared and sparse point allocation is rejected before the vendor chart parser runs',()=>{
  const C=drawings.ns.c[0],document=body=>parse(`<c:chartSpace xmlns:c="${C}">${body}</c:chartSpace>`);
  assert.throws(()=>charts.budget(document('<c:numCache><c:ptCount val="100000000"/></c:numCache>')),/budget/);
  assert.throws(()=>charts.budget(document('<c:numCache><c:pt idx="10000"/></c:numCache>')),/Invalid/);
  assert.throws(()=>charts.budget(document('<c:numCache><c:pt idx="9999"/></c:numCache><c:numCache><c:pt idx="9999"/></c:numCache>')),/allocation/);
  assert.throws(()=>charts.budget(document('<c:ser/>'.repeat(65))),/series/);
  assert.throws(()=>charts.budget(parse('<chartSpace xmlns="https://bad.invalid/"/>')),/unavailable/);
  assert.throws(()=>charts.budget(document('<x:numCache xmlns:x="https://bad.invalid/"><x:ptCount val="100000000"/></x:numCache>')),/namespace/);
});
test('unavailable external chart data cannot be invented or imported',()=>{
  const b=book(),xml=xmlFor(b),descriptor=drawings.objects('xl/worksheets/sheet1.xml',xml,()=>null).charts[0];
  const source=xml(descriptor.path);source.getElementsByTagNameNS(drawings.ns.c[0],'f')[0].textContent="'[private.xlsx]Data'!B1";
  assert.throws(()=>charts.prepare(descriptor,b,path=>path===descriptor.path?source:xml(path),serialize),/Uncached/);
});
test('the pinned parser keeps a large anchor canvas bounded while preserving its displayed extent',()=>{
  const b=book(),xml=xmlFor(b),descriptor=drawings.objects('xl/worksheets/sheet1.xml',xml,()=>null).charts[0];
  const projected=charts.prepare(descriptor,b,xml,serialize),source=charts.presentation(projected,100000,80000,api);
  assert.equal(source.width,2048);assert.ok(source.height<=2048);
  assert.throws(()=>charts.presentation(projected,Infinity,1,api),/Invalid/);
});
test('workbook theme follows its typed relationship instead of the first theme filename',()=>{
  const b=book(),xml=xmlFor(b),descriptor=drawings.objects('xl/worksheets/sheet1.xml',xml,()=>null).charts[0];
  const path=b.Directory.workbooks[0].replace(/^\/+/,''),rels=path.replace(/([^/]+)$/,'_rels/$1.rels');
  const source=xml(rels),theme=[...source.documentElement.children].find(node=>node.getAttribute('Type')===drawings.ns.r[0]+'/theme');
  assert.ok(theme);theme.setAttribute('Target','theme/alternate.xml');
  b.files['xl/theme/alternate.xml']={content:b.files['xl/theme/theme1.xml'].content};
  const override=at=>at===rels?source:xml(at);
  const projected=charts.prepare(descriptor,b,override,serialize);
  assert.equal(projected.theme,'xl/theme/alternate.xml');assert.equal(projected.incomplete,false);
  assert.deepEqual([...projected.maps.themes.keys()],['xl/theme/alternate.xml']);
  const parsed=charts.presentation(projected,400,240,api);api.materializeAllSlideNodes(parsed);
  assert.equal(parsed.slides[0].nodes.length,1);
  theme.setAttribute('TargetMode','External');theme.setAttribute('Target','https://example.invalid/theme.xml');
  const external=charts.prepare(descriptor,b,override,serialize);
  assert.equal(external.theme,null);assert.equal(external.incomplete,true);assert.equal(external.maps.themes.size,0);
});
