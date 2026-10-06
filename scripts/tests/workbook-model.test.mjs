import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
const model = require('../../app/src/main/assets/workbook-viewer/workbook-model.js');
const XLSX = require('../../app/src/main/assets/workbook-viewer/xlsx.full.min.js');
const bytes = readFileSync(new URL('../../app/src/androidTest/assets/workbook/rich.xlsx', import.meta.url));
const book = () => model.read(bytes);

test('reads real XLSX cached values, currency, percentage, dates and Unicode', () => {
  const b = book(), s = b.Sheets.Summary;
  assert.equal(model.text(s.A1), 'Quarterly résumé');
  assert.equal(model.text(s.B2), '$1,234.50');
  assert.equal(model.text(s.B4), '12.50%');
  assert.equal(model.text(s.B5), '2024-01-01');
  assert.equal(model.text(s.B6), '$2,469.00');
  assert.equal(s.B6.f, 'B2*2');
  assert.equal(model.text({f: 'WEBSERVICE("https://example.invalid")'}), '=WEBSERVICE("https://example.invalid")');
});
test('hides workbook sheets, rows and columns while preserving actual coordinates', () => {
  const b = book(), w = model.windowFor(b, 0);
  assert.deepEqual(model.sheets(b).map(s => s.name), ['Summary', 'Details']);
  assert.deepEqual(w.rows.slice(0, 4), [0, 1, 3, 4]);
  assert.deepEqual(w.cols.slice(0, 4), [0, 1, 3, 4]);
  assert.equal(model.windowFor(b, 2).chosen.index, 0);
});
test('large sparse ranges are bounded and final rows and columns remain reachable', () => {
  const b = book(), first = model.windowFor(b, 0), last = model.windowFor(b, 0, 204, 35);
  assert.equal(first.rows.length, 100); assert.equal(first.cols.length, 32);
  assert.deepEqual(last.rows, [204]); assert.deepEqual(last.cols, [35]);
  assert.equal(model.text(last.sheet.AJ205), 'Last visible cell');
  b.Sheets.Summary['!ref'] = 'A1:XFD1048576';
  const huge = model.windowFor(b, 0, 1048500, 16360);
  assert.equal(huge.rows.at(-1), 1048575); assert.equal(huge.cols.at(-1), 16383);
  assert.ok(huge.rows.length * huge.cols.length <= 3200);
});
test('merges retain their source when hidden cells or window boundaries clip them', () => {
  const b = book(), first = model.windowFor(b, 0);
  assert.deepEqual(first.merged.get('0:0'), {rowSpan: 1, colSpan: 2, address: 'A1'});
  b.Sheets.Summary['!merges'] = [{s: {r: 90, c: 0}, e: {r: 110, c: 3}}];
  const clipped = model.windowFor(b, 0, 100, 1);
  assert.deepEqual(clipped.merged.get('100:1'), {rowSpan: 11, colSpan: 2, address: 'A91'});
});
test('overlapping and malformed merged ranges fail rather than corrupting the grid', () => {
  const b = book(), merge = b.Sheets.Summary['!merges'][0];
  b.Sheets.Summary['!merges'].push(merge);
  assert.throws(() => model.windowFor(b, 0), /Overlapping/);
  b.Sheets.Summary['!merges'] = [{s:{r:0,c:0},e:{r:9999999,c:0}}];
  assert.throws(() => model.windowFor(b, 0), /Invalid/);
});
test('active links are rejected while cell text remains literal', () => {
  const b = book();
  assert.deepEqual(model.link(b.Sheets.Summary.A7), {external: 'https://example.invalid/workbook'});
  assert.equal(model.link(b.Sheets.Summary.A8), null);
  assert.equal(model.link({l:{Target:'data:text/html,unsafe'}}), null);
  assert.equal(model.link({l:{Target:'file:///secret'}}), null);
  assert.equal(model.text(b.Sheets.Summary.A10), '<script>unsafe</script>');
});
test('internal links resolve sheet names and reject hidden or invalid destinations', () => {
  const b = book();
  assert.deepEqual(model.destination('Details!$B$2', b, 0), {sheet:1,row:1,col:1});
  assert.deepEqual(model.destination('b12', b, 0), {sheet:0,row:11,col:1});
  assert.equal(model.destination('Hidden!A1', b, 0), null);
  assert.equal(model.destination('XFE1', b, 0), null);
  assert.equal(model.destination('A1048577', b, 0), null);
});
test('title styles preserve font, fill and alignment; theme tint is applied once', () => {
  const b = book(), css = model.style(b, 1);
  assert.equal(css.fontWeight, '700'); assert.equal(css.fontSize, '16pt');
  assert.equal(css.color, '#FFFFFF'); assert.equal(css.backgroundColor, '#17365D');
  assert.equal(css.textAlign, 'center');
  const theme = {Themes:{themeElements:{clrScheme:[{rgb:'000000'}]}}};
  assert.equal(model.color({theme:0,tint:.5,rgb:'808080'}, theme), '#808080');
  assert.equal(model.color({rgb:'url(https://example.invalid)'}, b), null);
});
test('empty sheets work and invalid declared ranges cannot allocate unbounded grids', () => {
  const b = book(); b.Sheets.Details = {};
  assert.equal(model.windowFor(b, 1).empty, true);
  for (const ref of ['XFE1', 'A0', 'A1048577', 'B2:A1', 'A1:B2:C3']) {
    b.Sheets.Summary['!ref'] = ref; assert.throws(() => model.windowFor(b, 0), /Invalid/);
  }
});
test('date1904 workbooks and formula errors retain authored display semantics', () => {
  const b = XLSX.utils.book_new();
  b.Workbook = {WBProps:{date1904:true}};
  XLSX.utils.book_append_sheet(b, {A1:{t:'n',v:0,z:'yyyy-mm-dd'}, B1:{t:'e',v:7}, '!ref':'A1:B1'}, 'Dates');
  const r = model.read(XLSX.write(b,{type:'buffer',bookType:'xlsx'}));
  assert.equal(model.text(r.Sheets.Dates.A1), '1904-01-01');
  assert.equal(model.text(r.Sheets.Dates.B1), '#DIV/0!');
});
