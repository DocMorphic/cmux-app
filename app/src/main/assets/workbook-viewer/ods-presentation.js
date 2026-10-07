/* Presentation metadata from validated ODF XML. No generated HTML or document CSS is used. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.CmuxOdsPresentation = factory();
})(globalThis, function() {
  'use strict';
  const ns = {
    office:'urn:oasis:names:tc:opendocument:xmlns:office:1.0',
    table:'urn:oasis:names:tc:opendocument:xmlns:table:1.0',
    style:'urn:oasis:names:tc:opendocument:xmlns:style:1.0',
    fo:'urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0',
    svg:'urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0'
  };
  const attr = (node, prefix, name) => node?.getAttributeNS(ns[prefix], name) || '';
  const children = (node, prefix, names) => Array.from(node?.children || []).filter(n => n.namespaceURI === ns[prefix] && names.includes(n.localName));
  const all = (node, prefix, name) => Array.from(node?.getElementsByTagNameNS(ns[prefix], name) || []);
  const key = (family, name) => family + '\0' + name;
  function length(value) {
    const match = /^(\d+(?:\.\d+)?)(pt|px|in|cm|mm|pc)$/.exec(value || '');
    if (!match) return null;
    const number = Number(match[1]) * {pt:1,px:.75,in:72,cm:72/2.54,mm:72/25.4,pc:12}[match[2]];
    return Number.isFinite(number) ? number : null;
  }
  const color = value => /^#[0-9a-f]{6}$/i.test(value || '') || value === 'transparent' ? value : null;
  function border(value) {
    if (value === 'none') return 'none';
    const parts = (value || '').split(/\s+/), width = length(parts[0]);
    if (parts.length !== 3 || width == null || !['solid','dotted','dashed','double'].includes(parts[1]) || !color(parts[2])) return null;
    return Math.min(6, width * 4/3) + 'px ' + parts[1] + ' ' + parts[2];
  }
  function css(properties, fonts) {
    const get = (prefix, name) => properties[ns[prefix] + '|' + name], out = {};
    const foreground = color(get('fo','color')), background = color(get('fo','background-color'));
    if (foreground) out.color = foreground;
    if (background) out.backgroundColor = background;
    const size = length(get('fo','font-size'));
    if (size != null) out.fontSize = Math.max(6, Math.min(96, size)) + 'pt';
    const font = get('fo','font-family') || fonts.get(get('style','font-name'));
    if (font && font.length <= 100) out.fontFamily = JSON.stringify(font.replace(/^['"]|['"]$/g,'')) + ', sans-serif';
    const weight = get('fo','font-weight'), italic = get('fo','font-style');
    if (/^(normal|bold|[1-9]00)$/.test(weight || '')) out.fontWeight = weight === 'normal' ? '400' : weight === 'bold' ? '700' : weight;
    if (['normal','italic','oblique'].includes(italic)) out.fontStyle = italic;
    const underline = get('style','text-underline-style'), strike = get('style','text-line-through-style');
    if (underline || strike) out.textDecoration = [underline && underline !== 'none' ? 'underline' : '', strike && strike !== 'none' ? 'line-through' : ''].filter(Boolean).join(' ') || 'none';
    const alignment = get('fo','text-align'), vertical = get('style','vertical-align');
    if (['start','end','left','right','center','justify'].includes(alignment)) out.textAlign = alignment;
    if (['top','middle','bottom'].includes(vertical)) out.verticalAlign = vertical;
    if (['wrap','no-wrap'].includes(get('fo','wrap-option'))) out.whiteSpace = get('fo','wrap-option') === 'wrap' ? 'pre-wrap' : 'pre';
    const edges = ['', '-left', '-right', '-top', '-bottom'];
    for (const side of edges) {
      const line = border(get('fo','border' + side));
      if (line) out['border' + (side ? side[1].toUpperCase() + side.slice(2) : '')] = line;
    }
    return out;
  }
  function lookup(ranges, index) {
    let lo = 0, hi = ranges.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1, item = ranges[mid];
      if (index < item.start) hi = mid - 1;
      else if (index > item.end) lo = mid + 1;
      else return item;
    }
    return null;
  }
  function apply(book, content, styles) {
    const definitions = new Map(), defaults = new Map(), fonts = new Map(), cache = new Map();
    for (const document of [styles, content].filter(Boolean)) {
      for (const font of all(document,'style','font-face')) fonts.set(attr(font,'style','name'),attr(font,'svg','font-family'));
      for (const type of ['default-style','style']) for (const node of all(document,'style',type)) {
        const family = attr(node,'style','family'), name = attr(node,'style','name'), properties = {};
        for (const part of children(node,'style',['text-properties','paragraph-properties','table-cell-properties','table-properties','table-row-properties','table-column-properties'])) {
          for (const a of Array.from(part.attributes || [])) properties[a.namespaceURI + '|' + a.localName] = a.value;
        }
        const value = {parent:attr(node,'style','parent-style-name'), properties};
        if (type === 'default-style') defaults.set(family,value); else definitions.set(key(family,name),value);
      }
    }
    function resolve(family, name, visiting = new Set()) {
      const id = key(family,name);
      if (cache.has(id)) return cache.get(id);
      if (visiting.has(id) || visiting.size >= 32) throw new Error('Invalid spreadsheet style inheritance');
      visiting.add(id);
      const definition = definitions.get(id);
      const result = Object.assign({}, defaults.get(family)?.properties,
        definition?.parent ? resolve(family,definition.parent,visiting) : {}, definition?.properties);
      visiting.delete(id); cache.set(id,result); return result;
    }
    const falseValue = value => value === 'false' || value === '0';
    const hidden = node => ['collapse','filter'].includes(attr(node,'table','visibility'));
    function repeat(node, attribute, maximum) {
      const raw = attr(node,'table',attribute) || '1', value = Number(raw);
      if (!/^\d+$/.test(raw) || !Number.isSafeInteger(value) || value < 1 || value > maximum) throw new Error('Invalid spreadsheet repeat');
      return value;
    }
    const body = all(content,'office','spreadsheet')[0];
    if (!body) throw new Error('Missing spreadsheet body');
    const tables = children(body,'table',['table']);
    for (const table of tables) {
      const name = attr(table,'table','name'), sheet = book.Sheets[name];
      if (!sheet) continue;
      const meta = {hidden:false, rows:[], cols:[], cellStyle: null};
      meta.hidden = falseValue(resolve('table',attr(table,'table','style-name'))[ns.table + '|display']);
      // Keep repeated rows/columns as intervals; a million empty tail rows never make a million metadata objects.
      function scan(parent, axis, groupHidden = false) {
        const plural = axis === 'rows' ? 'rows' : 'columns', singular = axis === 'rows' ? 'row' : 'column', maximum = axis === 'rows' ? 1048576 : 16384;
        const ranges = meta[axis];
        for (const node of children(parent,'table',['table-' + singular, 'table-' + singular + '-group', 'table-' + plural, 'table-header-' + plural])) {
          if (node.localName !== 'table-' + singular) {
            scan(node,axis,groupHidden || (node.localName.endsWith('-group') && falseValue(attr(node,'table','display'))));
            continue;
          }
          const start = ranges.length ? ranges.at(-1).end + 1 : 0, count = repeat(node,'number-' + plural + '-repeated',maximum);
          if (start + count > maximum) throw new Error('Spreadsheet coordinates exceed the preview range');
          const info = {start,end:start+count-1,hidden:groupHidden || hidden(node),defaultStyle:attr(node,'table','default-cell-style-name')};
          const style = resolve('table-' + singular,attr(node,'table','style-name'));
          const size = length(style[ns.style + '|' + singular + (axis === 'rows' ? '-height' : '-width')]);
          if (size != null) { if (axis === 'rows') info.hpt = size; else info.wpx = size * 4/3; }
          if (axis === 'rows') {
            info.cells = [];
            for (const cell of children(node,'table',['table-cell','covered-table-cell'])) {
              const first = info.cells.length ? info.cells.at(-1).end+1 : 0, copies = repeat(cell,'number-columns-repeated',16384);
              if (first+copies>16384) throw new Error('Spreadsheet columns exceed the preview range');
              info.cells.push({start:first,end:first+copies-1,style:attr(cell,'table','style-name')});
            }
          }
          ranges.push(info);
        }
      }
      scan(table,'rows'); scan(table,'cols');
      // The DOM/XML is released after projection; only compact style/range values remain.
      const cssCache = new Map();
      meta.cellStyle = (row, column) => {
        const rowInfo = lookup(meta.rows,row), columnInfo = lookup(meta.cols,column);
        const styleName = lookup(rowInfo?.cells || [],column)?.style || rowInfo?.defaultStyle || columnInfo?.defaultStyle || '';
        if (!cssCache.has(styleName)) cssCache.set(styleName,css(resolve('table-cell',styleName),fonts));
        return {...cssCache.get(styleName)};
      };
      sheet['!ods'] = meta;
    }
  }
  function documents(bytes, CFB, parse) {
    const archive = CFB.read(new Uint8Array(bytes),{type:'array'}), prefix = archive.FullPaths[0];
    function read(name, required) {
      const at = archive.FullPaths.indexOf(prefix+name);
      if (at < 0) { if (required) throw new Error('Missing ODS content'); return null; }
      const data = archive.FileIndex[at].content;
      const encoding = data[0]===0xff && data[1]===0xfe ? 'utf-16le' : data[0]===0xfe && data[1]===0xff ? 'utf-16be' : 'utf-8';
      return parse(new TextDecoder(encoding,{fatal:true}).decode(new Uint8Array(data)));
    }
    return {content:read('content.xml',true), styles:read('styles.xml',false)};
  }
  return {apply,documents,lookup,css,length,ns};
});
