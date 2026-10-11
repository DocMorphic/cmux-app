/* Worksheet charts use the unmodified, pinned presentation chart engine.
 * Only bounded chart/style/theme parts from the owned workbook are projected. */
(function(root,factory) {
  if(typeof module==='object' && module.exports) module.exports=factory(require('./workbook-drawings.js'),require('./workbook-model.js'));
  else root.CmuxWorkbookCharts=factory(root.CmuxWorkbookDrawings,root.CmuxWorkbookModel);
})(globalThis,function(drawings,model) {
  'use strict';
  const C=drawings.ns.c, A=drawings.ns.a, R=drawings.ns.r[0], P='http://schemas.openxmlformats.org/presentationml/2006/main';
  const REL=drawings.ns.rel[0], EMU=drawings.EMU, MAX_POINTS=16000;
  const attr=value=>String(value).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const rels=items=>`<Relationships xmlns="${REL}">${items.map(([id,type,target])=>
    `<Relationship Id="${attr(id)}" Type="${R}/${type}" Target="${attr(target)}"/>`).join('')}</Relationships>`;
  const elements=node=>Array.from(node.getElementsByTagNameNS('*','*'));
  const chartNode=(node,name)=>node.localName===name && C.includes(node.namespaceURI);
  function budget(document) {
    const root=document?.documentElement;
    if(!root || !chartNode(root,'chartSpace')) throw new Error('Chart part unavailable');
    const nodes=elements(root); if(nodes.length>50000) throw new Error('Chart structure exceeds preview budget');
    let points=0,series=0,allocated=0;
    for(const node of nodes) {
      if(['ser','pt','ptCount','numCache','strCache','numLit','strLit','lvl'].includes(node.localName) && !C.includes(node.namespaceURI))
        throw new Error('Chart namespace unavailable');
      if(chartNode(node,'ser') && ++series>64) throw new Error('Chart series exceeds preview budget');
      if(chartNode(node,'pt') && ++points>MAX_POINTS) throw new Error('Chart points exceed preview budget');
      if(chartNode(node,'pt') || chartNode(node,'ptCount')) {
        const raw=node.getAttribute(node.localName==='pt'?'idx':'val');
        if(!/^\d+$/.test(raw||'') || Number(raw)>10000 || (node.localName==='pt' && Number(raw)===10000))
          throw new Error('Invalid chart point index');
      }
      if(C.includes(node.namespaceURI) && ['numCache','strCache','numLit','strLit','lvl'].includes(node.localName)) {
        const children=Array.from(node.children), count=children.find(n=>chartNode(n,'ptCount'));
        const last=children.filter(n=>chartNode(n,'pt')).reduce((max,n)=>Math.max(max,Number(n.getAttribute('idx'))+1),0);
        allocated+=Math.max(last,Number(count?.getAttribute('val')||0));
        if(allocated>MAX_POINTS) throw new Error('Chart cache allocation exceeds preview budget');
      }
    }
  }
  function reference(formula,book) {
    if(typeof formula!=='string' || formula.length>1024 || /[\[\]]/.test(formula)) return null;
    const match=/^(?:'((?:[^']|'')+)'|([^'!]+))!\$?([a-z]{1,3})\$?([1-9]\d{0,6})(?::\$?([a-z]{1,3})\$?([1-9]\d{0,6}))?$/i.exec(formula.trim());
    if(!match) return null;
    const name=match[1]?.replace(/''/g,"'") || match[2],sheet=book.SheetNames?.includes(name)?book.Sheets?.[name]:null;
    const start=model.cellAddress(match[3].toUpperCase()+match[4]),end=model.cellAddress((match[5]||match[3]).toUpperCase()+(match[6]||match[4]));
    if(!sheet || !start || !end || end.r<start.r || end.c<start.c || (end.r!==start.r && end.c!==start.c)) return null;
    const count=(end.r-start.r+1)*(end.c-start.c+1);if(count>10000) throw new Error('Chart reference exceeds preview budget');
    const column=col=>{let n=col+1,value='';while(n){const digit=(n-1)%26;value=String.fromCharCode(65+digit)+value;n=Math.floor((n-1)/26);}return value;};
    const cells=[];for(let r=start.r;r<=end.r;r++) for(let c=start.c;c<=end.c;c++) cells.push(sheet[column(c)+(r+1)]);
    return cells;
  }
  function hydrate(document,book) {
    const nodes=elements(document.documentElement);let filled=0;
    for(let node of nodes) {
      if(!C.includes(node.namespaceURI) || !['numRef','strRef'].includes(node.localName)) continue;
      let kind=node.localName==='numRef'?'num':'str';const children=Array.from(node.children);
      if(children.some(n=>chartNode(n,kind+'Cache'))) continue;
      const formula=children.find(n=>chartNode(n,'f'))?.textContent, cells=reference(formula,book);
      if(!cells) throw new Error('Uncached chart reference unavailable');
      filled+=cells.length;if(filled>MAX_POINTS) throw new Error('Chart references exceed preview budget');
      // Some writers emit numRef for uncached text categories. Restore their
      // actual saved strings through strRef rather than turning labels into 0.
      if(kind==='num' && chartNode(node.parentElement,'cat') && cells.some(cell=>['s','str'].includes(cell?.t))) {
        const replacement=document.createElementNS(node.namespaceURI,'c:strRef');
        for(const child of Array.from(node.childNodes)) replacement.appendChild(child);
        node.replaceWith(replacement);node=replacement;kind='str';
      }
      const make=(name,text)=>{const child=document.createElementNS(node.namespaceURI,'c:'+name);if(text!=null)child.textContent=text;return child;};
      const cache=make(kind+'Cache');
      if(kind==='num') cache.appendChild(make('formatCode',cells[0]?.z || 'General'));
      const count=make('ptCount');count.setAttribute('val',String(cells.length));cache.appendChild(count);
      cells.forEach((cell,index)=>{
        const value=kind==='num'?(cell?.t==='n' && Number.isFinite(cell.v)?String(cell.v):null):cell==null?'':model.text(cell);
        if(value==null) return;
        const point=make('pt');point.setAttribute('idx',String(index));point.appendChild(make('v',value));cache.appendChild(point);
      });node.appendChild(cache);
    }
  }
  function part(book,path,xml) {
    const bytes=book.files?.[path]?.content;
    if(!bytes || bytes.length>2*1024*1024) throw new Error('Chart part exceeds preview budget');
    const value=xml(path); if(!value?.documentElement) throw new Error('Chart part unavailable');
    return value;
  }
  const resources=()=>({parts:new Map(),bytes:0});
  function prepare(descriptor,book,xml,serialize,shared=resources()) {
    function sharedPart(path) {
      if(shared.parts.has(path)) return shared.parts.get(path);
      const value=part(book,path,xml),bytes=book.files[path].content.length;
      if(shared.bytes+bytes>32*1024*1024) throw new Error('Worksheet chart resources exceed preview budget');
      const text=serialize(value);shared.bytes+=bytes;shared.parts.set(path,text);return text;
    }
    const document=part(book,descriptor.path,xml).cloneNode(true); budget(document);hydrate(document,book);budget(document);sharedPart(descriptor.path);
    const maps={charts:new Map([[descriptor.path,serialize(document)]]),chartRels:new Map(),chartStyles:new Map(),
      chartColors:new Map(),themes:new Map(),themeOverrides:new Map()};
    const relations=drawings.relationships(descriptor.path,xml), retained=[];
    let incomplete=false,bytes=book.files[descriptor.path].content.length;
    for(const [id,relation] of relations) {
      const type=relation.type?.split('/').at(-1), target=relation.target;
      const kind={chartStyle:'chartStyles',chartColorStyle:'chartColors',themeOverride:'themeOverrides',theme:'themes'}[type];
      // externalData is never opened: authored chart caches are the saved view.
      if(!kind) continue;
      if(!drawings.ns.r.some(uri=>relation.type===uri+'/'+type)) { incomplete=true;continue; }
      if(relation.external || !target) { incomplete=true;continue; }
      part(book,target,xml); bytes+=book.files[target].content.length;
      if(bytes>8*1024*1024) throw new Error('Chart resources exceed preview budget');
      maps[kind].set(target,sharedPart(target));retained.push([id,type,'/'+target]);
    }
    const workbook=(book.Directory?.workbooks?.[0]||'').replace(/^\/+/, '');
    const themes=workbook?Array.from(drawings.relationships(workbook,xml).values()).filter(relation=>
      drawings.ns.r.some(uri=>relation.type===uri+'/theme')):[];
    if(themes.length>1) throw new Error('Ambiguous workbook theme');
    const themeRelation=themes[0],theme=themeRelation && !themeRelation.external ? themeRelation.target : null;
    if(themeRelation && !theme) incomplete=true;
    if(theme) { part(book,theme,xml);bytes+=book.files[theme].content.length;
      if(bytes>8*1024*1024) throw new Error('Chart resources exceed preview budget');maps.themes.set(theme,sharedPart(theme)); }
    maps.chartRels.set(descriptor.path.replace(/([^/]+)$/,'_rels/$1.rels'),rels(retained));
    return {...descriptor,maps,theme,incomplete};
  }
  /** Adapt the engine's public buildPresentation input; no synthetic ZIP or upload. */
  function presentation(chart,width,height,api) {
    if(![width,height].every(v=>Number.isFinite(v) && v>0)) throw new Error('Invalid chart size');
    // Keep chart canvas allocation bounded even for a very large drawing anchor.
    const scale=Math.min(1,2048/width,2048/height), w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
    const cx=w*EMU,cy=h*EMU;
    const files={contentTypes:'',presentation:`<p:presentation xmlns:p="${P}" xmlns:r="${R}"><p:sldIdLst><p:sldId id="256" r:id="slide"/></p:sldIdLst><p:sldSz cx="${cx}" cy="${cy}"/></p:presentation>`,
      presentationRels:rels([['slide','slide','slides/slide1.xml']]),slides:new Map(),slideRels:new Map(),
      slideLayouts:new Map(),slideLayoutRels:new Map(),slideMasters:new Map(),slideMasterRels:new Map(),
      media:new Map(),fonts:new Map(),diagramDrawings:new Map(),...chart.maps};
    files.slides.set('ppt/slides/slide1.xml',`<p:sld xmlns:p="${P}" xmlns:a="${A[0]}" xmlns:r="${R}" xmlns:c="${C[0]}"><p:cSld><p:spTree><p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="2" name="Worksheet chart"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></p:xfrm><a:graphic><a:graphicData uri="${C[0]}"><c:chart r:id="chart"/></a:graphicData></a:graphic></p:graphicFrame></p:spTree></p:cSld></p:sld>`);
    files.slideRels.set('ppt/slides/_rels/slide1.xml.rels',rels([['chart','chart','/'+chart.path],['layout','slideLayout','../slideLayouts/slideLayout1.xml']]));
    files.slideLayouts.set('ppt/slideLayouts/slideLayout1.xml',`<p:sldLayout xmlns:p="${P}"><p:cSld><p:spTree/></p:cSld></p:sldLayout>`);
    files.slideLayoutRels.set('ppt/slideLayouts/_rels/slideLayout1.xml.rels',rels([['master','slideMaster','../slideMasters/slideMaster1.xml']]));
    files.slideMasters.set('ppt/slideMasters/slideMaster1.xml',`<p:sldMaster xmlns:p="${P}"><p:cSld><p:spTree/></p:cSld><p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" tx1="dk1" tx2="dk2" hlink="hlink" folHlink="folHlink"/></p:sldMaster>`);
    files.slideMasterRels.set('ppt/slideMasters/_rels/slideMaster1.xml.rels',rels(chart.theme?[['theme','theme','/'+chart.theme]]:[]));
    const result=api.buildPresentation(files,{lazySlides:true});
    if(result.slides.length!==1) throw new Error('Chart presentation unavailable');
    return result;
  }
  function render(chart,width,height,api,onError) {
    const source=presentation(chart,width,height,api);
    const handle=api.renderSlide(source,source.slides[0],{pdfjs:false,onNodeError:onError});
    handle.element.style.transform=`scale(${width/source.width},${height/source.height})`;
    handle.element.style.transformOrigin='top left';
    handle.element.setAttribute('role','img');handle.element.setAttribute('aria-label',chart.alt);
    return handle;
  }
  return {prepare,presentation,render,budget,reference,resources,MAX_POINTS};
});
