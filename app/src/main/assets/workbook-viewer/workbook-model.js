/* Bounded, read-only workbook projection. Shared by the viewer and Node regression checks. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./xlsx.full.min.js'));
  else root.CmuxWorkbookModel = factory(root.XLSX);
})(globalThis, function(XLSX) {
  'use strict';
  const ROWS = 100, COLS = 32, MAX_ROW = 1048575, MAX_COL = 16383;
  const integer = (value, max) => Number.isSafeInteger(value) && value >= 0 && value <= max;
  function cellAddress(value) {
    if (typeof value !== 'string' || !/^[A-Z]{1,3}[1-9][0-9]{0,6}$/i.test(value)) return null;
    const cell = XLSX.utils.decode_cell(value.toUpperCase());
    return integer(cell.r, MAX_ROW) && integer(cell.c, MAX_COL) ? cell : null;
  }
  function range(value) {
    const parts = String(value || 'A1').split(':');
    const s = cellAddress(parts[0]), e = cellAddress(parts[1] || parts[0]);
    if (parts.length > 2 || !s || !e || e.r < s.r || e.c < s.c) throw new Error('Invalid sheet range');
    return {s, e};
  }
  function sheets(book) {
    return book.SheetNames.map((name, index) => ({name, index}))
      .filter(item => !book.Workbook?.Sheets?.[item.index]?.Hidden && book.Sheets[item.name]);
  }
  function read(bytes) {
    const book = XLSX.read(bytes, {type: 'array', dense: false, bookFiles: true, bookVBA: false,
      cellHTML: false, cellStyles: true, cellFormula: true, cellText: true, cellNF: true});
    if (book.bookType !== 'xlsx' || !book.SheetNames.length || book.SheetNames.length > 2048) throw new Error('Unsupported workbook');
    for (const name of book.SheetNames) if (book.Sheets[name]?.['!ref']) range(book.Sheets[name]['!ref']);
    if (!sheets(book).length) throw new Error('No visible worksheets');
    return book;
  }
  function windowFor(book, index, row = 0, col = 0) {
    const visible = sheets(book);
    const chosen = visible.find(item => item.index === index) || visible[0];
    if (!chosen) throw new Error('No visible worksheets');
    const sheet = book.Sheets[chosen.name], bounds = range(sheet['!ref']);
    row = Math.min(Math.max(integer(row, MAX_ROW) ? row : 0, bounds.s.r), bounds.e.r);
    col = Math.min(Math.max(integer(col, MAX_COL) ? col : 0, bounds.s.c), bounds.e.c);
    const rows = [], cols = [];
    for (let r = row; r <= bounds.e.r && rows.length < ROWS; r++) if (!sheet['!rows']?.[r]?.hidden) rows.push(r);
    for (let c = col; c <= bounds.e.c && cols.length < COLS; c++) if (!sheet['!cols']?.[c]?.hidden) cols.push(c);
    const merged = new Map(), covered = new Set();
    const merges = sheet['!merges'] || [];
    if (merges.length > 4096) throw new Error('Too many merged ranges');
    for (const merge of merges) {
      if (!integer(merge.s?.r, MAX_ROW) || !integer(merge.e?.r, MAX_ROW) || !integer(merge.s?.c, MAX_COL) ||
          !integer(merge.e?.c, MAX_COL) || merge.s.r > merge.e.r || merge.s.c > merge.e.c) throw new Error('Invalid merged range');
      const rr = rows.filter(r => r >= merge.s.r && r <= merge.e.r), cc = cols.filter(c => c >= merge.s.c && c <= merge.e.c);
      if (!rr.length || !cc.length) continue;
      const key = `${rr[0]}:${cc[0]}`;
      for (const r of rr) for (const c of cc) {
        const slot = `${r}:${c}`;
        if (covered.has(slot)) throw new Error('Overlapping merged ranges');
        covered.add(slot);
      }
      merged.set(key, {rowSpan: rr.length, colSpan: cc.length, address: XLSX.utils.encode_cell(merge.s)});
    }
    return {chosen, sheet, bounds, rows, cols, row, col, merged, covered, empty: !sheet['!ref']};
  }
  function text(cell) {
    if (!cell) return '';
    // Cached values are shown as saved by the author. Never execute formulas or retrieve external data.
    if (cell.v == null && cell.f) return '=' + cell.f;
    return String(cell.w ?? XLSX.utils.format_cell(cell) ?? '');
  }
  function link(cell) {
    const target = cell?.l?.Target;
    if (typeof target !== 'string' || target.length > 8192) return null;
    if (target.startsWith('#')) return {internal: target.substring(1)};
    try { const parsed = new URL(target); return ['https:', 'http:', 'mailto:', 'tel:'].includes(parsed.protocol) ? {external: target} : null; }
    catch (_) { return null; }
  }
  function color(value, book) {
    if (!value) return null;
    const theme = book.Themes?.themeElements?.clrScheme?.[value.theme]?.rgb;
    let rgb = theme || value.rgb?.slice(-6);
    const index = value.indexed ?? value.index;
    const palette = ['000000', 'FFFFFF', 'FF0000', '00FF00', '0000FF', 'FFFF00', 'FF00FF', '00FFFF'];
    if (!/^[\da-f]{6}$/i.test(rgb || '') && integer(index, 15)) rgb = palette[index % 8];
    if (!/^[\da-f]{6}$/i.test(rgb || '')) return null;
    const tint = Number(value.tint);
    if ((theme || value.theme == null) && Number.isFinite(tint) && tint >= -1 && tint <= 1 && tint !== 0) rgb = [0, 2, 4].map(i => {
      const v = parseInt(rgb.slice(i, i + 2), 16);
      return Math.round(tint < 0 ? v * (1 + tint) : v + (255 - v) * tint).toString(16).padStart(2, '0');
    }).join('');
    return '#' + rgb;
  }
  function style(book, styleIndex = 0) {
    const xf = book.Styles?.CellXf?.[styleIndex] || {}, font = book.Styles?.Fonts?.[xf.fontId] || {};
    const fill = book.Styles?.Fills?.[xf.fillId], align = xf.alignment || {}, css = {};
    if (font.bold) css.fontWeight = '700';
    if (font.italic) css.fontStyle = 'italic';
    if (font.underline || font.strike) css.textDecoration = [font.underline ? 'underline' : '', font.strike ? 'line-through' : ''].filter(Boolean).join(' ');
    if (typeof font.name === 'string' && font.name.length <= 100) css.fontFamily = JSON.stringify(font.name) + ', sans-serif';
    if (Number.isFinite(font.sz)) css.fontSize = Math.max(6, Math.min(96, font.sz)) + 'pt';
    if (color(font.color, book)) css.color = color(font.color, book);
    if (fill?.patternType === 'solid' && color(fill.fgColor, book)) css.backgroundColor = color(fill.fgColor, book);
    if (['left', 'center', 'right', 'justify'].includes(align.horizontal)) css.textAlign = align.horizontal;
    if (['top', 'center', 'bottom'].includes(align.vertical)) css.verticalAlign = align.vertical === 'center' ? 'middle' : align.vertical;
    if (align.wrapText) css.whiteSpace = 'pre-wrap';
    if (Number.isFinite(Number(align.indent))) css.paddingLeft = Math.min(20, Math.max(0, Number(align.indent))) + 'em';
    return css;
  }
  function destination(raw, book, current) {
    const split = raw.lastIndexOf('!');
    let name = split < 0 ? book.SheetNames[current] : raw.slice(0, split);
    if (name.startsWith("'") && name.endsWith("'")) name = name.slice(1, -1).replace(/''/g, "'");
    const at = cellAddress(raw.slice(split + 1).replace(/\$/g, ''));
    const sheet = sheets(book).find(item => item.name === name);
    return sheet && at ? {sheet: sheet.index, row: at.r, col: at.c} : null;
  }
  return {read, sheets, range, windowFor, text, link, style, color, cellAddress, destination, ROWS, COLS};
});
