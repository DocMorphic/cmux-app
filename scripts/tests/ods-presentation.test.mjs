import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
const require=createRequire(import.meta.url);
const ods=require('../../app/src/main/assets/workbook-viewer/ods-presentation.js');
const model=require('../../app/src/main/assets/workbook-viewer/workbook-model.js');
const XLSX=require('../../app/src/main/assets/workbook-viewer/xlsx.full.min.js');
const {ns}=ods;
function node(tag, attrs={}, children=[]) {
  const [prefix,localName]=tag.split(':');
  const attributes=Object.entries(attrs).map(([key,value])=>{const [p,localName]=key.split(':');return {namespaceURI:ns[p],localName,value}});
  return {namespaceURI:ns[prefix],localName,children,childNodes:children,attributes,
    getAttributeNS(uri,name){return attributes.find(a=>a.namespaceURI===uri && a.localName===name)?.value ?? null},
    getElementsByTagNameNS(uri,name){return children.flatMap(n=>[...(n.namespaceURI===uri && n.localName===name?[n]:[]),...n.getElementsByTagNameNS(uri,name)])}};
}
const prop=(tag,attrs)=>node('style:'+tag,attrs);
const style=(name,family,parts=[],parent)=>node('style:style',{'style:name':name,'style:family':family,...(parent?{'style:parent-style-name':parent}:{})},parts);
const cell=(styleName)=>node('table:table-cell',styleName?{'table:style-name':styleName}:{});
const row=(attrs={},cells=[cell()])=>node('table:table-row',attrs,cells);
const table=(name,items=[],attrs={})=>node('table:table',{'table:name':name,...attrs},items);
const content=(tables,styles=[])=>node('office:document-content',{},[node('office:automatic-styles',{},styles),node('office:body',{},[node('office:spreadsheet',{},tables)])]);
const book=()=>({SheetNames:['Main','Hidden'],Sheets:{Main:{A1:{v:'hello'},'!ref':'A1:C10'},Hidden:{A1:{v:'hidden'},'!ref':'A1'}},Workbook:{}});
test('inherited table visibility removes hidden sheets from selection, restore and links',()=>{
  const b=book();
  ods.apply(b,content([table('Main'),table('Hidden',[],{'table:style-name':'child'})],[
    style('base','table',[prop('table-properties',{'table:display':'false'})]),style('child','table',[],'base')
  ]));
  assert.deepEqual(model.sheets(b),[{name:'Main',index:0}]);
  assert.equal(model.windowFor(b,1).chosen.name,'Main');
  assert.equal(model.destination('Hidden!A1',b,0),null);
});
test('row and column filtering, collapsed groups and repeats keep authored coordinates',()=>{
  const b=book();ods.apply(b,content([table('Main',[
    node('table:table-column',{}),node('table:table-column',{'table:visibility':'collapse'}),node('table:table-column',{}),
    row(),row({'table:number-rows-repeated':'3','table:visibility':'filter'}),
    node('table:table-row-group',{'table:display':'0'},[row(),row()]),row()
  ])]));
  const w=model.windowFor(b,0);
  assert.deepEqual(w.rows,[0,6,7,8,9]); assert.deepEqual(w.cols,[0,2]);
  assert.equal(b.Sheets.Main['!ods'].rows.length,5);
});
test('cell style overrides row default, then column default, with inherited named/default styles',()=>{
  const b=book();const styles=node('office:document-styles',{},[
    node('style:default-style',{'style:family':'table-cell'},[prop('text-properties',{'fo:font-size':'12pt'})]),
    style('base','table-cell',[prop('text-properties',{'fo:color':'#123456','fo:font-weight':'bold'})]),
    style('column','table-cell',[prop('table-cell-properties',{'fo:background-color':'#FFFF00'})],'base'),
    style('row','table-cell',[prop('text-properties',{'fo:font-weight':'normal'})],'base')
  ]);
  ods.apply(b,content([table('Main',[
    node('table:table-column',{'table:default-cell-style-name':'column','table:number-columns-repeated':'3'}),
    row(),row({'table:default-cell-style-name':'row'},[cell(),cell('explicit')])
  ])],[style('explicit','table-cell',[prop('text-properties',{'fo:font-style':'italic','fo:color':'#FF0000'})],'base')]),styles);
  const m=b.Sheets.Main['!ods'];
  assert.deepEqual(m.cellStyle(0,0),{color:'#123456',backgroundColor:'#FFFF00',fontSize:'12pt',fontWeight:'700'});
  assert.equal(m.cellStyle(1,0).fontWeight,'400');assert.equal(m.cellStyle(1,0).backgroundColor,undefined);
  assert.equal(m.cellStyle(1,1).color,'#FF0000');assert.equal(m.cellStyle(1,1).fontStyle,'italic');
});
test('column and row sizes use typed lengths and repeated metadata stays compact',()=>{
  const b=book();b.Sheets.Main['!ref']='A1:C1048576';
  ods.apply(b,content([table('Main',[
    node('table:table-column',{'table:number-columns-repeated':'3','table:style-name':'wide'}),
    row({'table:number-rows-repeated':'1048575','table:visibility':'collapse'}),row({'table:style-name':'tall'})
  ])],[style('wide','table-column',[prop('table-column-properties',{'style:column-width':'2in'})]),
      style('tall','table-row',[prop('table-row-properties',{'style:row-height':'36pt'})])]));
  assert.equal(b.Sheets.Main['!ods'].rows.length,2);
  assert.deepEqual(model.windowFor(b,0).rows,[1048575]);
  assert.equal(model.axisInfo(b.Sheets.Main,'cols',2).wpx,192);
  assert.equal(model.axisInfo(b.Sheets.Main,'rows',1048575).hpt,36);
});
test('cell CSS rejects external and executable values while retaining safe borders and text properties',()=>{
  const p={}; for(const [name,value] of Object.entries({'color':'url(https://bad.invalid)','background-color':'#AABBCC','font-family':'A";color:red','font-size':'999999in','font-weight':'700','font-style':'italic','border':'1pt solid #123456','border-left':'url(x)','wrap-option':'wrap','text-align':'center'}))p[ns.fo+'|'+name]=value;
  const css=ods.css(p,new Map());
  assert.equal(css.color,undefined);assert.equal(css.backgroundColor,'#AABBCC');assert.equal(css.borderLeft,undefined);
  assert.equal(css.fontFamily,JSON.stringify('A";color:red')+', sans-serif');assert.equal(css.fontSize,'96pt');
  assert.equal(css.border,'1.3333333333333333px solid #123456');assert.equal(css.whiteSpace,'pre-wrap');
});
test('style cycles and invalid repeated coordinates fail explicitly',()=>{
  assert.throws(()=>ods.apply(book(),content([table('Main',[],{'table:style-name':'a'})],[style('a','table',[],'b'),style('b','table',[],'a')])) ,/inheritance/);
  assert.throws(()=>ods.apply(book(),content([table('Main',[row({'table:number-rows-repeated':'1e9'})])])),/repeat/);
});
test('real validated fixture archive exposes only the selected XML parts to the metadata parser',()=>{
  const bytes=readFileSync(new URL('../../app/src/androidTest/assets/workbook/open-document.ods',import.meta.url));
  const parts=ods.documents(bytes,XLSX.CFB,s=>s);
  assert.match(parts.content,/OpenDocument 日本語/);assert.match(parts.styles,/office:document-styles/);
  assert.equal(Object.keys(parts).length,2);
});
test('paging skips hidden tails and previous pages cross a million-row hidden interval',()=>{
  const b=book();b.Sheets.Main['!ref']='A1:C1048576';
  ods.apply(b,content([table('Main',[
    node('table:table-column',{'table:number-columns-repeated':'2'}),node('table:table-column',{'table:visibility':'collapse'}),
    row(),row({'table:number-rows-repeated':'1048574','table:visibility':'filter'}),row()
  ])]));
  const first=model.windowFor(b,0), last=model.windowFor(b,0,1048575,1);
  assert.deepEqual(first.rows,[0,1048575]);assert.equal(first.nextColumn,null);assert.equal(first.nextRow,null);
  assert.equal(last.previousRow,0);assert.equal(last.previousColumn,0);assert.equal(last.nextColumn,null);
  const hiddenTail=model.windowFor(b,0,1048575,2);
  assert.deepEqual(hiddenTail.cols,[]);assert.equal(hiddenTail.previousColumn,0);assert.equal(hiddenTail.nextColumn,null);
});

const txt=value=>({nodeType:3,nodeValue:value,getElementsByTagNameNS(){return []}});
const paragraph=(parts)=>node('text:p',{},parts);
const styledCell=(parts,attrs={})=>node('table:table-cell',attrs,parts);
const richBook=(parts,styles=[],attrs={})=>{const b=book();ods.apply(b,content([table('Main',[row({},[styledCell(parts,attrs)])])],styles));return b.Sheets.Main['!ods'];};
test('nested ODF spans inherit cell styles and explicitly reset decoration without changing saved text',()=>{
  const m=richBook([paragraph([txt(' Parent '),node('text:span',{'text:style-name':'bold'},[
    txt('bold '),node('text:span',{'text:style-name':'reset'},[txt('plain')])]),txt(' tail ')])],[
    style('base','table-cell',[prop('text-properties',{'fo:color':'#123456','style:text-underline-style':'solid'})]),
    style('bold','text',[prop('text-properties',{'fo:font-weight':'bold'})]),
    style('reset','text',[prop('text-properties',{'fo:font-weight':'normal','style:text-underline-style':'none','fo:font-style':'italic'})])
  ],{'table:style-name':'base'});
  const runs=m.cellRuns(0,0);assert.equal(runs.map(r=>r.text).join(''),'Parent bold plain tail');
  assert.equal(runs.find(r=>r.text==='bold').style.fontWeight,'700');
  assert.deepEqual(runs.find(r=>r.text==='plain').style,{color:'#123456',fontWeight:'400',fontStyle:'italic',textDecoration:'none'});
  assert.equal(runs[0].style.textDecoration,'underline');
});
test('ODF whitespace crosses span boundaries while explicit spaces, tabs, line breaks and paragraphs survive',()=>{
  const m=richBook([paragraph([txt('  A \n'),node('text:span',{},[txt('  B  ')]),node('text:s',{'text:c':'3'}),txt('C'),node('text:tab'),txt('D'),node('text:line-break'),txt('E   ')]),paragraph([txt('日本語')])]);
  assert.equal(m.cellRuns(0,0).map(r=>r.text).join(''),'A B    C\tD\nE\n日本語');
});
test('individual links retain their own destination and unsafe targets remain inert',()=>{
  const m=richBook([paragraph([node('text:a',{'xlink:href':'#Main.A1'},[txt('internal')]),txt(' + '),
    node('text:a',{'xlink:href':'https://example.com'},[txt('external')]),
    node('text:a',{'xlink:href':'javascript:alert(1)'},[txt('blocked')]),txt('<script>literal</script>')])]);
  const runs=m.cellRuns(0,0);
  assert.deepEqual(model.link({l:{Target:runs.find(r=>r.text==='internal').href}}),{internal:'Main!A1'});
  assert.deepEqual(model.link({l:{Target:runs.find(r=>r.text==='external').href}}),{external:'https://example.com'});
  assert.equal(model.link({l:{Target:runs.find(r=>r.text==='blocked').href}}),null);
  assert.equal(runs.at(-1).text,'<script>literal</script>');
});
test('rich repeats remain compact and excessive depth, expansion or visible runs use the saved-value fallback',()=>{
  const b=book();ods.apply(b,content([table('Main',[row({'table:number-rows-repeated':'1000'},[
    styledCell([paragraph([txt('Repeat')])],{'table:number-columns-repeated':'1000'})])])]));
  const m=b.Sheets.Main['!ods'];assert.equal(m.rows.length,1);assert.equal(m.rows[0].cells.length,1);
  assert.equal(m.cellRuns(999,999)[0].text,'Repeat');assert.equal(m.cellRuns(0,0,0),null);
  assert.equal(richBook([paragraph([node('text:s',{'text:c':'999999999'})])]).cellRuns(0,0),null);
  let deep=txt('deep');for(let i=0;i<34;i++)deep=node('text:span',{},[deep]);
  assert.equal(richBook([paragraph([deep])]).cellRuns(0,0),null);
});
test('annotations and ruby glosses are excluded; unsupported fields keep the existing saved display',()=>{
  const m=richBook([paragraph([txt('Base'),node('office:annotation',{},[paragraph([txt('secret note')])]),
    node('text:ruby',{},[node('text:ruby-base',{},[txt('字')]),node('text:ruby-text',{},[txt('gloss')])])])]);
  assert.equal(m.cellRuns(0,0).map(r=>r.text).join(''),'Base字');
  assert.equal(richBook([paragraph([node('text:date',{},[txt('today')])])]).cellRuns(0,0),null);
});

test('ODF cell links normalize quoted, dotted, encoded and current-sheet coordinates',()=>{
  const b=book();b.SheetNames.push("Résumé.a'b");b.Sheets["Résumé.a'b"]={'!ref':'A1:B3'};
  const target=ods.linkTarget("#'R%C3%A9sum%C3%A9.a''b'.$B$3");
  assert.equal(target,"#'Résumé.a''b'!$B$3");
  assert.deepEqual(model.destination(model.link({l:{Target:target}}).internal,b,0),{sheet:2,row:2,col:1});
  assert.deepEqual(model.destination(ods.linkTarget('#.$A$1').slice(1),b,0),{sheet:0,row:0,col:0});
  assert.equal(ods.linkTarget('#$Main.A1'),'#Main!A1');
  assert.equal(ods.linkTarget('#bad%ZZ.A1'),'');
  assert.equal(ods.linkTarget('https://example.com/a.b'),'https://example.com/a.b');
});
