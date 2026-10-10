'use strict';
(async () => {
  const model = CmuxWorkbookModel, content = document.getElementById('content');
  const error = document.getElementById('error'), selector = document.getElementById('sheets');
  try {
    const response = await fetch('document.zip', {credentials: 'omit', cache: 'no-store'});
    if (!response.ok) throw new Error('Document unavailable');
    const bytes = await response.arrayBuffer();
    let book = model.read(bytes);
    if (book.bookType === 'ods') {
      const parts = CmuxOdsPresentation.documents(bytes, XLSX.CFB, source => {
        const xml = new DOMParser().parseFromString(source, 'application/xml');
        if (xml.getElementsByTagName('parsererror').length) throw new Error('Invalid ODS XML');
        return xml;
      }, xml => new XMLSerializer().serializeToString(xml));
      if (parts.normalizedArchive) book = model.readArchive(parts.normalizedArchive);
      CmuxOdsPresentation.apply(book, parts.content, parts.styles);
    }
    const drawings = CmuxWorkbookDrawings, pictureCache = new Map(), imageURLs = new Map();
    let pictureResize;
    window.addEventListener('pagehide', () => { pictureResize?.disconnect(); for (const url of imageURLs.values()) URL.revokeObjectURL(url); imageURLs.clear(); });
    function limitation(incomplete) {
      const note = document.getElementById('limitations'); note.hidden = !incomplete;
      note.textContent = book.bookType === 'ods' ? 'Some formatting, charts and drawings aren’t shown here. Use Viewer actions to open the original workbook.' : 'Some charts or drawing objects aren’t shown here. Use Viewer actions to open the original workbook.';
    }
    const memories = new Map(), styleIndices = new Map(), richCells = new Map();
    const nodes = (node, name) => Array.from(node.getElementsByTagNameNS('*', name));
    function xml(path) {
      const bytes = book.files?.[path.replace(/^\/+/, '')]?.content;
      if (!bytes) return null;
      const parsed = new DOMParser().parseFromString(new TextDecoder().decode(bytes), 'application/xml');
      return nodes(parsed, 'parsererror').length ? null : parsed;
    }
    function resolve(base, target) {
      if (!target || target.includes('\\') || /^[a-z]+:/i.test(target)) return null;
      const parts = target.startsWith('/') ? [] : base.split('/').slice(0, -1);
      for (const part of target.split('/')) {
        if (part === '..') { if (!parts.length) return null; parts.pop(); }
        else if (part && part !== '.') parts.push(part);
      }
      return parts.join('/');
    }
    const main = (book.Directory?.workbooks?.[0] || '').replace(/^\/+/, '');
    const mainXml = xml(main), relationshipPath = main.replace(/([^/]+)$/, '_rels/$1.rels');
    const rels = xml(relationshipPath);
    const paths = new Map();
    if (mainXml && rels) {
      const relations = new Map(nodes(rels, 'Relationship').filter(n => n.getAttribute('TargetMode') !== 'External')
        .map(n => [n.getAttribute('Id'), resolve(main, n.getAttribute('Target'))]));
      nodes(mainXml, 'sheet').forEach(n => paths.set(n.getAttribute('name'), relations.get(n.getAttribute('r:id') ||
        n.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id'))));
    }
    const stylesXml = xml(book.Directory?.style || ''), borders = stylesXml ? nodes(stylesXml, 'border') : [];
    const stringsXml = xml(book.Directory?.strs?.[0] || '');
    const sharedStrings = stringsXml ? model.children(stringsXml.documentElement, 'si') : [];
    function pictures(name) {
      if (!pictureCache.has(name)) {
        const result = book.bookType === 'ods' ? {images:[],unsupported:1} :
          paths.get(name) ? drawings.images(paths.get(name),xml,path => book.files?.[path]?.content) : {images:[],unsupported:1};
        drawings.includeBounds(book.Sheets[name],result.images); pictureCache.set(name,result);
      }
      return pictureCache.get(name);
    }
    function paintPictures(wrapper, table, next, result) {
      if(!wrapper.isConnected) return;
      wrapper.querySelector('.worksheet-pictures')?.remove();
      if (!result.images.length || !next.rows.length || !next.cols.length) return;
      const tableRect=table.getBoundingClientRect(), heading=table.tHead.rows[0].cells[1].getBoundingClientRect();
      const rows=Array.from(table.tBodies[0].rows), top=rows[0].getBoundingClientRect().top;
      const rowBounds=rows.map(row=>{const r=row.getBoundingClientRect();return [r.top-top,r.bottom-top];});
      const columnBounds=Array.from(table.tHead.rows[0].cells).slice(1).map(cell=>{const r=cell.getBoundingClientRect();return [r.left-heading.left,r.right-heading.left];});
      const x=drawings.axis(next.sheet,'cols',next.cols,columnBounds),y=drawings.axis(next.sheet,'rows',next.rows,rowBounds);
      const layer=document.createElement('div'); layer.className='worksheet-pictures';
      const width=tableRect.right-heading.left,height=rows.at(-1).getBoundingClientRect().bottom-top;
      Object.assign(layer.style,{left:heading.left-tableRect.left+'px',top:top-tableRect.top+'px',width:width+'px',height:height+'px'});
      for (const picture of result.images) {
        const rect=drawings.rectangle(picture,x,y);
        if(!rect) { limitation(true); continue; }
        // Rotation can bring a picture into view even when its unrotated edge is outside.
        const visual=drawings.visualBounds(picture,rect);
        if(visual.left+visual.width<=0 || visual.top+visual.height<=0 || visual.left>=width || visual.top>=height) continue;
        const frame=document.createElement('div');frame.className='workbook-picture';
        Object.assign(frame.style,{left:rect.left+'px',top:rect.top+'px',width:rect.width+'px',height:rect.height+'px',
          transform:`rotate(${picture.rotation}deg) scale(${picture.flipX?-1:1},${picture.flipY?-1:1})`});
        const crop=document.createElement('div');crop.className='workbook-picture-crop';
        const image=document.createElement('img');image.alt=picture.alt;image.draggable=false;
        const dx=1-picture.crop.l-picture.crop.r,dy=1-picture.crop.t-picture.crop.b;
        Object.assign(image.style,{width:100/dx+'%',height:100/dy+'%',left:-100*picture.crop.l/dx+'%',top:-100*picture.crop.t/dy+'%'});
        if(!imageURLs.has(picture.path)) imageURLs.set(picture.path,URL.createObjectURL(new Blob([picture.bytes],{type:picture.mime})));
        image.src=imageURLs.get(picture.path);image.addEventListener('error',()=>{if(frame.isConnected) { frame.remove();limitation(true); }},{once:true});
        crop.appendChild(image);
        if(picture.link) {
          const link=document.createElement('a');link.href=picture.link.external || '#';link.setAttribute('aria-label',picture.alt);
          if(picture.link.internal) link.addEventListener('click',event=>{
            event.preventDefault();const point=model.destination(picture.link.internal,book,next.chosen.index);
            if(point) render(point.sheet,point.row,point.col); else error.textContent='This link destination is unavailable.';
          });
          link.appendChild(crop);frame.appendChild(link);
        } else frame.appendChild(crop);
        layer.appendChild(frame);
      }
      wrapper.appendChild(layer);
    }
    function cellStyles(name) {
      if (styleIndices.has(name)) return styleIndices.get(name);
      const indices = new Map(), rich = new Map(), source = paths.get(name) && xml(paths.get(name));
      if (source) nodes(source, 'c').forEach(cell => {
        const index = Number(cell.getAttribute('s') || 0), address = cell.getAttribute('r');
        if (!model.cellAddress(address)) return;
        if (Number.isInteger(index) && index >= 0 && index < (book.Styles?.CellXf?.length || 0)) indices.set(address, index);
        let item;
        if (cell.getAttribute('t') === 'inlineStr') item = model.children(cell, 'is')[0];
        else if (cell.getAttribute('t') === 's') {
          const raw = model.children(cell, 'v')[0]?.textContent;
          const at = /^\d+$/.test(raw || '') ? Number(raw) : -1;
          if (Number.isSafeInteger(at) && at >= 0) item = sharedStrings[at];
        }
        if (item && model.children(item, 'r').length) rich.set(address, item);
      });
      styleIndices.set(name, indices); richCells.set(name, rich); return indices;
    }
    function applyBorder(cell, index) {
      const border = borders[book.Styles?.CellXf?.[index]?.borderId];
      if (!border) return;
      for (const side of ['left', 'right', 'top', 'bottom']) {
        const edge = Array.from(border.children).find(n => n.localName === side), type = edge?.getAttribute('style');
        if (!type || type === 'none') continue;
        const colorNode = edge.children[0], colorValue = {};
        if (colorNode) for (const attr of Array.from(colorNode.attributes)) colorValue[attr.name] = ['theme', 'indexed', 'tint'].includes(attr.name) ? Number(attr.value) : attr.value;
        const width = type === 'thick' ? 3 : type.startsWith('medium') || type === 'double' ? 2 : 1;
        const line = type === 'double' ? 'double' : /dash/i.test(type) ? 'dashed' : type === 'dotted' ? 'dotted' : 'solid';
        cell.style['border' + side[0].toUpperCase() + side.slice(1)] = `${width}px ${line} ${model.color(colorValue, book) || '#222'}`;
      }
    }
    const element = (tag, text) => { const node = document.createElement(tag); if (text != null) node.textContent = text; return node; };
    for (const sheet of model.sheets(book)) { const option = element('option', sheet.name); option.value = sheet.index; selector.appendChild(option); }
    let current;
    function render(index, row = 0, col = 0, initial = false) {
      try {
        const visible=model.sheets(book), chosen=visible.find(sheet=>sheet.index===index) || visible[0];
        const pictureResult=pictures(chosen.name);
        const next = model.windowFor(book, index, row, col), table = element('table');
        limitation(book.bookType==='ods' || pictureResult.unsupported>0);
        table.setAttribute('aria-label', next.chosen.name);
        const indices = cellStyles(next.chosen.name), head = element('thead'), headings = element('tr');
        headings.appendChild(element('th', ''));
        const columns = element('colgroup'), rowNumbers = element('col');
        rowNumbers.style.width = '52px'; columns.appendChild(rowNumbers);
        let tableWidth = 52;
        for (const c of next.cols) {
          const col = element('col'), metadata = model.axisInfo(next.sheet, 'cols', c);
          const columnWidth = drawings.columnWidth(metadata);
          col.style.width = columnWidth + 'px'; tableWidth += columnWidth; columns.appendChild(col);
          const th = element('th', XLSX.utils.encode_col(c)); th.scope = 'col'; headings.appendChild(th);
        }
        // Fixed table layout only honors colgroup widths with an explicit table width.
        table.style.width = tableWidth + 'px';
        head.appendChild(headings); table.append(columns, head);
        const body = element('tbody');
        let runBudget = 16000; // Shared strings can otherwise multiply into millions of DOM nodes.
        for (const r of next.rows) {
          const tr = element('tr'), th = element('th', r + 1); th.scope = 'row'; tr.appendChild(th);
          const height = model.axisInfo(next.sheet, 'rows', r)?.hpt;
          if (Number.isFinite(height)) tr.style.height = Math.min(600, Math.max(12, height)) + 'pt';
          for (const c of next.cols) {
            const key = `${r}:${c}`, merge = next.merged.get(key);
            if (next.covered.has(key) && !merge) continue;
            const address = merge?.address || XLSX.utils.encode_cell({r, c}), source = next.sheet[address];
            const td = element('td'); td.dataset.cell = address;
            if (merge) { td.rowSpan = merge.rowSpan; td.colSpan = merge.colSpan; }
            if (source?.t === 'n') td.className = 'number';
            const styleIndex = indices.get(address) || 0, sourcePosition = XLSX.utils.decode_cell(address);
            const ods = next.sheet['!ods'];
            const cellStyle = ods?.cellStyle(sourcePosition.r,sourcePosition.c) || model.style(book, styleIndex);
            const odsRuns = runBudget > 0 ? ods?.cellRuns(sourcePosition.r,sourcePosition.c,runBudget) : null;
            const target = odsRuns ? null : model.link(source), label = model.text(source);
            const decoration = cellStyle.textDecoration || (target ? 'underline' : '');
            // Decorations on ancestors cannot be canceled by a run's explicit u=none/strike=0.
            delete cellStyle.textDecoration;
            function attachLink(node, link) {
              node.href = link.external || '#';
              if (link.internal) node.addEventListener('click', event => {
                event.preventDefault(); const point = model.destination(link.internal, book, next.chosen.index);
                if (point) render(point.sheet, point.row, point.col); else error.textContent = 'This link destination is unavailable.';
              });
            }
            const textHost = element(target ? 'a' : 'span');
            if (target) { textHost.style.textDecoration = 'none'; attachLink(textHost,target); }
            const runs = odsRuns || (runBudget > 0 ? model.richText(richCells.get(next.chosen.name)?.get(address), book, decoration, runBudget) : null);
            if (runs) runBudget -= runs.length;
            if (runs && (odsRuns || runs.map(run => run.text).join('') === label)) {
              for (const run of runs) {
                const link = odsRuns ? model.link({l:{Target:run.href}}) : null;
                const span = element(link ? 'a' : 'span', run.text); span.className = 'rich-run';
                Object.assign(span.style, run.style); if (link) attachLink(span,link);
                textHost.appendChild(span);
              }
            } else {
              const span = element('span', label); span.style.textDecoration = decoration || 'none'; textHost.appendChild(span);
            }
            td.appendChild(textHost);
            Object.assign(td.style, cellStyle); applyBorder(td, styleIndex);
            tr.appendChild(td);
          }
          body.appendChild(tr);
        }
        table.appendChild(body);
        pictureResize?.disconnect();
        const wrapper=element('div');wrapper.className='worksheet';wrapper.appendChild(table);
        content.replaceChildren(next.empty ? element('p', 'This worksheet is empty.') : wrapper);
        if(!next.empty) {
          paintPictures(wrapper,table,next,pictureResult);
          pictureResize=new ResizeObserver(()=>paintPictures(wrapper,table,next,pictureResult));pictureResize.observe(table);
        }
        current = next; selector.value = next.chosen.index; memories.set(next.chosen.index, {row: next.row, col: next.col});
        document.getElementById('left').disabled = next.previousColumn == null;
        document.getElementById('up').disabled = next.previousRow == null;
        document.getElementById('right').disabled = next.nextColumn == null;
        document.getElementById('down').disabled = next.nextRow == null;
        document.getElementById('range').textContent = next.empty ? 'Empty worksheet' : !next.rows.length || !next.cols.length ?
          'No visible cells in this range.' : `Rows ${next.rows[0] + 1}–${next.rows.at(-1) + 1} · Columns ${XLSX.utils.encode_col(next.cols[0])}–${XLSX.utils.encode_col(next.cols.at(-1))}`;
        error.textContent = '';
        if (!initial) window.scrollTo(0, 0);
        CmuxMarkdownBridge.postMessage(JSON.stringify({action: 'workbookState', sheet: next.chosen.index, row: next.row, col: next.col}));
      } catch (_) { error.textContent = 'This worksheet range can’t be displayed. Choose another sheet or cell.'; if (!current) throw new Error('No readable range'); }
    }
    selector.addEventListener('change', () => { const index = Number(selector.value), position = memories.get(index); render(index, position?.row, position?.col); });
    for (const [id,key,rowAxis] of [['up','previousRow',true],['down','nextRow',true],['left','previousColumn',false],['right','nextColumn',false]]) document.getElementById(id).addEventListener('click', () => {
      const coordinate=current[key];
      if(coordinate != null) render(current.chosen.index,rowAxis ? coordinate : current.row,rowAxis ? current.col : coordinate);
    });
    const go = () => { const point = model.destination(document.getElementById('address').value.trim(), book, current.chosen.index);
      if (point) render(point.sheet, point.row, point.col); else error.textContent = 'Enter a cell address, such as B12.'; };
    document.getElementById('go').addEventListener('click', go);
    document.getElementById('address').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); go(); } });
    const initial = new URLSearchParams(location.hash.slice(1));
    render(Number(initial.get('sheet')), Number(initial.get('row')), Number(initial.get('col')), true);
    window.__cmuxWorkbookReady = true;
    CmuxMarkdownBridge.postMessage(JSON.stringify({action: 'officeReady'}));
  } catch (_) { content.replaceChildren(); CmuxMarkdownBridge.postMessage(JSON.stringify({action: 'officeFailed'})); }
})();
