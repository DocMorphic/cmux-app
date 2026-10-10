import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
const require=createRequire(import.meta.url);
const drawing=require('../../app/src/main/assets/workbook-viewer/workbook-drawings.js');
const model=require('../../app/src/main/assets/workbook-viewer/workbook-model.js');
const {ns,EMU}=drawing;
function element(kind,tag,attrs={},children=[],textContent='') {
  return {namespaceURI:ns[kind][0],localName:tag,children,textContent,
    getAttribute:key=>Object.hasOwn(attrs,key)?String(attrs[key]):null,
    getAttributeNS:(uri,key)=>ns.r.includes(uri)?attrs['r:'+key]??null:null};
}
const e=element;
const doc=root=>({documentElement:root});
const marker=(tag,col=0,row=0,x=0,y=0)=>e('xdr',tag,{},['col','row','colOff','rowOff'].map((name,i)=>e('xdr',name,{},[],String([col,row,x,y][i]))));
const picture=(attributes={},fill=[],transform={},hyperlink=[],image='photo')=>e('xdr','pic',{},[
  e('xdr','nvPicPr',{},[e('xdr','cNvPr',{descr:'Résumé <script> literal',...attributes},hyperlink)]),
  e('xdr','blipFill',{},[e('a','blip',{'r:embed':image}),...fill]),e('xdr','spPr',{},[e('a','xfrm',transform)])]);
const one=(parts=[marker('from'),picture(),e('xdr','ext',{cx:160*EMU,cy:80*EMU})])=>e('xdr','oneCellAnchor',{},parts);
const rel=(id,type,target,external=false)=>e('rel','Relationship',{Id:id,Type:ns.r[0]+'/'+type,Target:target,...external?{TargetMode:'External'}:{}});
const png=Uint8Array.from([137,80,78,71,13,10,26,10]);
function fixture(anchors=[one()],relations=[rel('photo','image','../media/photo%20one.png')]) {
  const documents=new Map([
    ['xl/worksheets/sheet1.xml',doc(e('sheet','worksheet',{},[e('sheet','drawing',{'r:id':'drawing'})]))],
    ['xl/worksheets/_rels/sheet1.xml.rels',doc(e('rel','Relationships',{},[rel('drawing','drawing','../drawings/drawing1.xml')]))],
    ['xl/drawings/drawing1.xml',doc(e('xdr','wsDr',{},anchors))],
    ['xl/drawings/_rels/drawing1.xml.rels',doc(e('rel','Relationships',{},relations))]
  ]);
  let reads=0;const xml=path=>documents.get(path);
  return {documents,xml,read:()=>{reads++;return png},reads:()=>reads,
    result(){return drawing.images('xl/worksheets/sheet1.xml',xml,this.read)}};
}
test('package references normalize relative, absolute and escaped names without leaving the archive',()=>{
  assert.equal(drawing.resolve('xl/drawings/drawing1.xml','../media/photo%20one.png'),'xl/media/photo one.png');
  assert.equal(drawing.resolve('xl/drawings/drawing1.xml','/xl/media/p.png'),'xl/media/p.png');
  for(const bad of ['../../../outside.png','https://bad.invalid/p.png','file:secret','../media/%2fp.png','../media/p.png#x','../media/p.png?x','../media/%00.png','../media/%zz','../media/\\p.png']) assert.equal(drawing.resolve('xl/drawing.xml',bad),null,bad);
});
test('real XLSX fixture carries original PNG bytes and all four authored picture forms',()=>{
  const book=model.read(readFileSync(new URL('../../app/src/androidTest/assets/workbook/pictures.xlsx',import.meta.url)));
  assert.deepEqual(model.sheets(book).map(s=>s.name),['Summary','Details','Pictures only']);
  assert.equal(drawing.mime(book.files['xl/media/photo one.png'].content),'image/png');
  assert.match(new TextDecoder().decode(book.files['xl/drawings/drawing1.xml'].content),/twoCellAnchor/);
  assert.match(new TextDecoder().decode(book.files['xl/drawings/drawing1.xml'].content),/absoluteAnchor/);
});
test('one-cell pictures retain marker offsets, physical extent and literal descriptions',()=>{
  const f=fixture([one([marker('from',3,15,10*EMU,5*EMU),picture(),e('xdr','ext',{cx:160*EMU,cy:80*EMU})])]);
  const {images,unsupported}=f.result();assert.equal(unsupported,0);assert.equal(f.reads(),1);
  assert.deepEqual(images[0].from,{col:3,row:15,x:10,y:5});assert.equal(images[0].width,160);assert.equal(images[0].height,80);
  assert.equal(images[0].path,'xl/media/photo one.png');assert.equal(images[0].alt,'Résumé <script> literal');
});
test('two-cell and absolute pictures project their own anchor scheme',()=>{
  const f=fixture([e('xdr','twoCellAnchor',{},[marker('from',1,2),marker('to',3,5),picture()]),
    e('xdr','absoluteAnchor',{},[e('xdr','pos',{x:20*EMU,y:10*EMU}),e('xdr','ext',{cx:40*EMU,cy:50*EMU}),picture()])]);
  const result=f.result(),x=drawing.axis({},'cols'),y=drawing.axis({},'rows');
  assert.deepEqual(drawing.rectangle(result.images[0],x,y),{left:110,top:52,width:220,height:78});
  assert.deepEqual(drawing.rectangle(result.images[1],x,y),{left:20,top:10,width:40,height:50});
});
test('crop, signed rotation and horizontal/vertical flip metadata are preserved',()=>{
  const result=fixture([one([marker('from'),picture({},[e('a','srcRect',{l:25000,t:-10000,r:10000})],{rot:-5400000,flipH:'true',flipV:'1'}),e('xdr','ext',{cx:EMU,cy:EMU})])]).result();
  const p=result.images[0];assert.deepEqual(p.crop,{l:.25,t:-.1,r:.1,b:0});assert.equal(p.rotation,-90);assert.equal(p.flipX,true);assert.equal(p.flipY,true);
});
test('invalid crops, missing markers, malformed extents and graphic objects are counted as unavailable',()=>{
  const f=fixture([one([marker('from'),picture({},[e('a','srcRect',{l:80000,r:30000})]),e('xdr','ext',{cx:EMU,cy:EMU})]),
    one([picture(),e('xdr','ext',{cx:EMU,cy:EMU})]),one([marker('from'),picture(),e('xdr','ext',{cx:'Infinity',cy:EMU})]),e('xdr','twoCellAnchor',{},[marker('from'),marker('to'),e('xdr','graphicFrame')])]);
  assert.deepEqual(f.result(),{images:[],unsupported:4});
});
test('external/active pictures and foreign namespace lookalikes never read their target',()=>{
  const external=fixture([one()],[rel('photo','image','https://bad.invalid/p.png',true)]);assert.equal(external.result().unsupported,1);assert.equal(external.reads(),0);
  const f=fixture();f.documents.get('xl/drawings/drawing1.xml').documentElement.namespaceURI='https://bad.invalid';
  assert.equal(f.result().unsupported,1);assert.equal(f.reads(),0);
});
test('picture hyperlinks share the existing safe gesture destination policy',()=>{
  const f=fixture([one([marker('from'),picture({},[],{},[e('a','hlinkClick',{'r:id':'link'})]),e('xdr','ext',{cx:EMU,cy:EMU})])],
    [rel('photo','image','../media/p.png'),rel('link','hyperlink','https://example.invalid/support',true)]);
  assert.deepEqual(f.result().images[0].link,{external:'https://example.invalid/support'});
  f.documents.get('xl/drawings/_rels/drawing1.xml.rels').documentElement.children[1]=rel('link','hyperlink','javascript:unsafe',true);
  assert.equal(f.result().images[0].link,null);
});
test('duplicate relationship identities and excessive drawing counts cannot be silently projected',()=>{
  assert.throws(()=>fixture([one()],[rel('photo','image','a.png'),rel('photo','image','b.png')]).result(),/Ambiguous/);
  assert.throws(()=>fixture(Array.from({length:257},()=>one())).result(),/Too many/);
});
test('sparse axes honor hidden dimensions and bound positioning at the final worksheet row',()=>{
  const sheet={'!rows':{1:{hidden:true},3:{hpt:30}},'!cols':{1:{hidden:true},3:{wpx:200}}};
  const x=drawing.axis(sheet,'cols'),y=drawing.axis(sheet,'rows');
  assert.equal(x.prefix(4),420);assert.equal(y.prefix(4),92);
  assert.equal(y.prefix(1048576),1048576*26-26+14);
  assert.equal(y.locate(y.prefix(1048575)+5).index,1048575);
});
test('visible row geometry corrects wrapped cells and images clip consistently across range windows',()=>{
  const x=drawing.axis({},'cols',[2,3],[[0,110],[110,220]]),y=drawing.axis({},'rows',[100,101],[[0,60],[60,100]]);
  assert.equal(y.project(100),0);assert.equal(y.project(101),60);assert.equal(y.project(102),100);
  assert.equal(y.project(99),-26);assert.equal(x.project(1),-110);
  assert.deepEqual(drawing.rectangle({from:{col:1,row:99,x:0,y:0},to:{col:3,row:102,x:0,y:0}},x,y),{left:-110,top:-26,width:220,height:126});
});
test('image-only sheets and pictures beyond the cell range become reachable without allocating cells',()=>{
  const sheet={};drawing.includeBounds(sheet,[{from:{col:1,row:11,x:10,y:5},width:120,height:60}]);
  assert.equal(sheet['!ref'],'A1:C14');assert.equal(Object.keys(sheet).length,1);
  const book={SheetNames:['Pictures'],Sheets:{Pictures:sheet}};assert.equal(model.windowFor(book,0).empty,false);
  drawing.includeBounds(sheet,[{from:{col:16383,row:1048575,x:0,y:0},width:100,height:20}]);
  assert.equal(sheet['!ref'],'A1:XFD1048576');
  const last=model.windowFor(book,0,1048575,16383);assert.deepEqual(last.rows,[1048575]);assert.deepEqual(last.cols,[16383]);
});
test('reversed/zero anchors and unsupported binary image payloads never produce misleading geometry',()=>{
  assert.equal(drawing.rectangle({from:{col:2,row:2,x:0,y:0},to:{col:1,row:1,x:0,y:0}},drawing.axis({},'cols'),drawing.axis({},'rows')),null);
  assert.equal(drawing.mime(new TextEncoder().encode('<svg onload="unsafe"/>')),null);
  assert.equal(drawing.mime(new Uint8Array(16*1024*1024+1)),null);
});
test('percentage crop spellings preserve the same physical source rectangle',()=>{
  const p=fixture([one([marker('from'),picture({},[e('a','srcRect',{l:'25%',t:'12.5%'})]),e('xdr','ext',{cx:EMU,cy:EMU})])]).result().images[0];
  assert.deepEqual(p.crop,{l:.25,t:.125,r:0,b:0});
});
test('rotated pictures expand image-only worksheet bounds to include the painted corners',()=>{
  const sheet={},p={absolute:{x:0,y:0},width:20,height:100,rotation:90};
  const bounds=drawing.visualBounds(p,{left:0,top:0,width:20,height:100});
  assert.ok(Math.abs(bounds.left+40)<1e-8);assert.ok(Math.abs(bounds.top-40)<1e-8);
  assert.ok(Math.abs(bounds.width-100)<1e-8);assert.ok(Math.abs(bounds.height-20)<1e-8);
  drawing.includeBounds(sheet,[{absolute:{x:0,y:0},width:100,height:20,rotation:90}]);
  assert.equal(sheet['!ref'],'A1:A3');
});
test('the per-sheet image byte budget counts unique resources and excludes an oversized aggregate',()=>{
  const anchors=['a','b','c'].map(id=>one([marker('from'),picture({},[],{},[],id),e('xdr','ext',{cx:EMU,cy:EMU})]));
  const f=fixture(anchors,['a','b','c'].map(id=>rel(id,'image','../media/'+id+'.png')));
  const bytes=new Uint8Array(12*1024*1024);bytes.set(png);f.read=()=>bytes;
  const result=f.result();assert.equal(result.images.length,2);assert.equal(result.unsupported,1);
});
