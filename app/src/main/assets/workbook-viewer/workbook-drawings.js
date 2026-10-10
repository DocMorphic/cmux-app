/* Offline SpreadsheetML pictures. No document URL is ever fetched. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./workbook-model.js'));
  else root.CmuxWorkbookDrawings = factory(root.CmuxWorkbookModel);
})(globalThis, function(model) {
  'use strict';
  const ns = {
    sheet: ['http://schemas.openxmlformats.org/spreadsheetml/2006/main', 'http://purl.oclc.org/ooxml/spreadsheetml/main'],
    xdr: ['http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing', 'http://purl.oclc.org/ooxml/drawingml/spreadsheetDrawing'],
    a: ['http://schemas.openxmlformats.org/drawingml/2006/main', 'http://purl.oclc.org/ooxml/drawingml/main'],
    r: ['http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'http://purl.oclc.org/ooxml/officeDocument/relationships'],
    rel: ['http://schemas.openxmlformats.org/package/2006/relationships']
  };
  const EMU = 9525, MAX_IMAGES = 256, MAX_BYTES = 32 * 1024 * 1024;
  const is = (node, kind, name) => node?.localName === name && ns[kind].includes(node.namespaceURI);
  const children = (node, kind, name) => Array.from(node?.children || []).filter(n => is(n, kind, name));
  const first = (node, kind, name) => children(node, kind, name)[0];
  const ref = (node, name) => ns.r.map(uri => node?.getAttributeNS(uri, name)).find(value => value) || null;
  function number(value, min = 0, max = 1e12) {
    if (typeof value !== 'string' || !/^-?\d+$/.test(value.trim())) throw new Error('Invalid picture coordinate');
    const n = Number(value); if (!Number.isSafeInteger(n) || n < min || n > max) throw new Error('Invalid picture coordinate');
    return n;
  }
  function resolve(base, target) {
    if (typeof target !== 'string' || target.length > 512 || /[\\:#?\x00-\x1f]/.test(target)) return null;
    const parts = target.startsWith('/') ? [] : base.split('/').slice(0, -1);
    try {
      for (const raw of target.split('/')) {
        const part = decodeURIComponent(raw);
        if (/[\\/:#?\x00-\x1f]/.test(part)) return null;
        if (part === '..') { if (!parts.length) return null; parts.pop(); }
        else if (part && part !== '.') parts.push(part);
      }
    } catch (_) { return null; }
    return parts.length ? parts.join('/') : null;
  }
  function relationships(base, xml) {
    const source = xml(base.replace(/([^/]+)$/, '_rels/$1.rels'))?.documentElement;
    if (!is(source, 'rel', 'Relationships')) return new Map();
    const result = new Map();
    for (const node of children(source, 'rel', 'Relationship')) {
      const id = node.getAttribute('Id'), type = node.getAttribute('Type');
      if (!id || result.has(id)) throw new Error('Ambiguous picture relationship');
      const external = node.getAttribute('TargetMode') === 'External';
      result.set(id, {type, external, target: external ? node.getAttribute('Target') : resolve(base, node.getAttribute('Target'))});
    }
    return result;
  }
  const relationType = (relation, type) => ns.r.some(uri => relation?.type === uri + '/' + type);
  function marker(node, boundary = false) {
    return {col: number(first(node,'xdr','col')?.textContent,0,boundary ? 16384 : 16383),
      row: number(first(node,'xdr','row')?.textContent,0,boundary ? 1048576 : 1048575),
      x: number(first(node,'xdr','colOff')?.textContent) / EMU,
      y: number(first(node,'xdr','rowOff')?.textContent) / EMU};
  }
  function percentage(value) {
    if (typeof value === 'string' && /^-?\d+(\.\d+)?%$/.test(value.trim())) {
      const result=Number(value.trim().slice(0,-1))/100;
      if (result>=-1 && result<=1) return result;
      throw new Error('Invalid picture crop');
    }
    return number(value,-100000,100000)/100000;
  }
  function mime(bytes) {
    if (bytes?.length > 16 * 1024 * 1024) return null;
    if (bytes?.length >= 8 && [137,80,78,71,13,10,26,10].every((v,i) => bytes[i] === v)) return 'image/png';
    if (bytes?.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
    if (bytes?.length >= 6 && String.fromCharCode(...bytes.slice(0,6)).match(/^GIF8[79]a$/)) return 'image/gif';
    if (bytes?.length >= 2 && bytes[0] === 66 && bytes[1] === 77) return 'image/bmp';
    return null;
  }
  function images(sheetPath, xml, readBytes) {
    const result = [], resources = new Set(); let bytes = 0, unsupported = 0;
    const sheet = xml(sheetPath)?.documentElement;
    if (!is(sheet,'sheet','worksheet')) return {images:result,unsupported:1};
    const sheetRels = relationships(sheetPath,xml);
    unsupported += children(sheet,'sheet','legacyDrawing').length;
    for (const drawingRef of children(sheet,'sheet','drawing')) {
      const relation = sheetRels.get(ref(drawingRef,'id'));
      if (!relationType(relation,'drawing') || relation.external || !relation.target) { unsupported++; continue; }
      const drawing = xml(relation.target)?.documentElement;
      if (!is(drawing,'xdr','wsDr')) { unsupported++; continue; }
      const rels = relationships(relation.target,xml);
      for (const anchor of Array.from(drawing.children)) {
        if (result.length + unsupported >= MAX_IMAGES) throw new Error('Too many workbook pictures');
        try {
          if (!['oneCellAnchor','twoCellAnchor','absoluteAnchor'].some(tag => is(anchor,'xdr',tag))) throw new Error('Unsupported drawing');
          const pictures = children(anchor,'xdr','pic');
          if (pictures.length !== 1 || Array.from(anchor.children).some(n => ['sp','grpSp','graphicFrame','cxnSp'].some(tag => is(n,'xdr',tag)))) throw new Error('Unsupported drawing');
          const pic = pictures[0], fill = first(pic,'xdr','blipFill'), blip = first(fill,'a','blip');
          const imageRel = rels.get(ref(blip,'embed'));
          if (!relationType(imageRel,'image') || imageRel.external || !imageRel.target || first(fill,'a','tile')) throw new Error('Unsupported image');
          const data = readBytes(imageRel.target), type = mime(data);
          if (!type) throw new Error('Unsupported image');
          if (!resources.has(imageRel.target)) { resources.add(imageRel.target); bytes += data.length; }
          if (bytes > MAX_BYTES) throw new Error('Workbook pictures are too large');
          const properties = first(first(pic,'xdr','nvPicPr'),'xdr','cNvPr');
          const transform = first(first(pic,'xdr','spPr'),'a','xfrm');
          const crop = first(fill,'a','srcRect'), cropping = {};
          for (const edge of ['l','t','r','b']) cropping[edge] = percentage(crop?.getAttribute(edge) || '0');
          if (1 - cropping.l - cropping.r <= 0 || 1 - cropping.t - cropping.b <= 0) throw new Error('Invalid picture crop');
          const image = {path:imageRel.target,bytes:data,mime:type,crop:cropping,
            alt:(properties?.getAttribute('descr') || properties?.getAttribute('title') || properties?.getAttribute('name') || 'Embedded image').slice(0,1024),
            rotation:number(transform?.getAttribute('rot') || '0',-21600000,21600000)/60000,
            flipX:['1','true'].includes(transform?.getAttribute('flipH')),flipY:['1','true'].includes(transform?.getAttribute('flipV'))};
          const hyperlink = rels.get(ref(first(properties,'a','hlinkClick'),'id'));
          if (relationType(hyperlink,'hyperlink')) image.link = model.link({l:{Target:hyperlink.target}});
          if (is(anchor,'xdr','absoluteAnchor')) {
            const pos = first(anchor,'xdr','pos'); image.absolute = {x:number(pos?.getAttribute('x'),-1e12)/EMU,y:number(pos?.getAttribute('y'),-1e12)/EMU};
          } else image.from = marker(first(anchor,'xdr','from'));
          if (is(anchor,'xdr','twoCellAnchor')) image.to = marker(first(anchor,'xdr','to'),true);
          else { const extent = first(anchor,'xdr','ext'); image.width=number(extent?.getAttribute('cx'),1)/EMU; image.height=number(extent?.getAttribute('cy'),1)/EMU; }
          result.push(image);
        } catch (_) { unsupported++; }
      }
    }
    return {images:result,unsupported};
  }
  function columnWidth(info) {
    const width = info?.wpx ?? (Number(info?.wch)*7+10);
    return Number.isFinite(width) ? Math.min(800,Math.max(32,width)) : 110;
  }
  function rowHeight(info) {
    return Number.isFinite(info?.hpt) ? Math.max(26,Math.min(600,Math.max(12,info.hpt))*4/3) : 26;
  }
  /** Sparse prefix sums never enumerate all million rows to position an image. */
  function axis(sheet, kind, visible = [], boundaries = []) {
    const fallback = kind === 'cols' ? 110 : 26, maximum = kind === 'cols' ? 16384 : 1048576;
    const size = info => info?.hidden ? 0 : kind === 'cols' ? columnWidth(info) : rowHeight(info);
    const entries = Object.entries(sheet[kind === 'cols' ? '!cols' : '!rows'] || {})
      .filter(([key]) => /^\d+$/.test(key) && Number(key)<maximum).map(([key,info]) => [Number(key),size(info)-fallback]).sort((a,b)=>a[0]-b[0]);
    const sums = [0]; for (const [,delta] of entries) sums.push(sums.at(-1)+delta);
    function lower(values, target) { let l=0,r=values.length; while(l<r) { const m=(l+r)>>>1; if(values[m][0]<target) l=m+1; else r=m; } return l; }
    const prefix = index => index*fallback+sums[lower(entries,index)];
    function locate(value) {
      let l=0,r=maximum; while(l<r) { const m=Math.ceil((l+r)/2); if(prefix(m)<=value) l=m; else r=m-1; }
      return {index:Math.min(l,maximum-1),offset:value-prefix(Math.min(l,maximum-1))};
    }
    const points = visible.map((index,i)=>[index,boundaries[i][0],boundaries[i][1]]);
    function project(index,offset=0) {
      if (!points.length) return prefix(index)+offset;
      const at=lower(points,index), origin=points[0][1];
      if (at<points.length && points[at][0]===index) return points[at][1]-origin+offset;
      if (at===0) return prefix(index)-prefix(points[0][0])+offset;
      const previous=points[at-1];
      return previous[2]-origin+prefix(index)-prefix(previous[0]+1)+offset;
    }
    return {prefix,locate,project};
  }
  function rectangle(image, x, y) {
    const point = (which,metric,axisName,offsetName) => which ? metric.project(which[axisName],which[offsetName]) : null;
    const absX=image.absolute && x.locate(image.absolute.x), absY=image.absolute && y.locate(image.absolute.y);
    const left=image.from ? point(image.from,x,'col','x') : x.project(absX.index,absX.offset);
    const top=image.from ? point(image.from,y,'row','y') : y.project(absY.index,absY.offset);
    const width=image.to ? point(image.to,x,'col','x')-left : image.width;
    const height=image.to ? point(image.to,y,'row','y')-top : image.height;
    if (![left,top,width,height].every(Number.isFinite) || width<=0 || height<=0 || width>100000 || height>100000) return null;
    return {left,top,width,height};
  }
  function visualBounds(image,rect) {
    const radians=(image.rotation || 0)*Math.PI/180;
    const width=Math.abs(rect.width*Math.cos(radians))+Math.abs(rect.height*Math.sin(radians));
    const height=Math.abs(rect.width*Math.sin(radians))+Math.abs(rect.height*Math.cos(radians));
    return {left:rect.left+(rect.width-width)/2,top:rect.top+(rect.height-height)/2,width,height};
  }
  function includeBounds(sheet, pictures) {
    if (!pictures.length) return;
    const x=axis(sheet,'cols'),y=axis(sheet,'rows');
    const bounds=model.range(sheet['!ref']);
    for(const image of pictures) {
      const unrotated=rectangle(image,x,y); if(!unrotated) continue;
      const rect=visualBounds(image,unrotated);
      const c=x.locate(rect.left+rect.width-1e-6).index,r=y.locate(rect.top+rect.height-1e-6).index;
      bounds.e.c=Math.max(bounds.e.c,c); bounds.e.r=Math.max(bounds.e.r,r);
      bounds.s.c=Math.min(bounds.s.c,x.locate(Math.max(0,rect.left)).index); bounds.s.r=Math.min(bounds.s.r,y.locate(Math.max(0,rect.top)).index);
    }
    const address = p => { let c=p.c+1,s=''; while(c) { const v=(c-1)%26;s=String.fromCharCode(65+v)+s;c=Math.floor((c-1)/26); } return s+(p.r+1); };
    sheet['!ref']=address(bounds.s)+':'+address(bounds.e);
  }
  return {ns,resolve,relationships,images,mime,axis,rectangle,visualBounds,includeBounds,columnWidth,rowHeight,EMU};
});
