#!/usr/bin/env python3
"""Create the small authored ODS fixture; no external documents or office install needed."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_STORED

content = '''<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
 xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
 xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
 xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:of="urn:oasis:names:tc:opendocument:xmlns:of:1.2" office:version="1.2">
 <office:automatic-styles>
  <number:currency-style style:name="usd"><number:currency-symbol>$</number:currency-symbol><number:number number:decimal-places="2" number:min-integer-digits="1" number:grouping="true"/></number:currency-style>
  <style:style style:name="money" style:family="table-cell" style:data-style-name="usd"/>
 </office:automatic-styles>
 <office:body><office:spreadsheet>
  <table:table table:name="Résumé">
   <table:table-row><table:table-cell office:value-type="string" table:number-columns-spanned="2"><text:p>OpenDocument 日本語</text:p></table:table-cell><table:covered-table-cell/></table:table-row>
   <table:table-row><table:table-cell office:value-type="string"><text:p>Revenue</text:p></table:table-cell><table:table-cell office:value-type="currency" office:currency="USD" office:value="1234.5" table:style-name="money"><text:p>$1,234.50</text:p></table:table-cell></table:table-row>
   <table:table-row><table:table-cell office:value-type="string"><text:p>Cached total</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="2469" table:formula="of:=[.B2]*2"><text:p>2469</text:p></table:table-cell></table:table-row>
   <table:table-row table:number-rows-repeated="3"><table:table-cell table:number-columns-repeated="2" office:value-type="float" office:value="7"><text:p>7</text:p></table:table-cell></table:table-row>
   <table:table-row><table:table-cell office:value-type="string"><text:p><text:a xlink:href="#Details.B2">Details</text:a></text:p></table:table-cell></table:table-row>
   <table:table-row><table:table-cell office:value-type="string"><text:p><text:a xlink:href="javascript:window.__unsafeOds=true">Blocked link</text:a></text:p></table:table-cell></table:table-row>
   <table:table-row><table:table-cell office:value-type="string"><text:p>&lt;script&gt;unsafe&lt;/script&gt;</text:p></table:table-cell></table:table-row>
   <table:table-row><table:table-cell office:value-type="string"><text:p>A<text:s text:c="3"/>B</text:p></table:table-cell></table:table-row>
  </table:table>
  <table:table table:name="Details">
   <table:table-row><table:table-cell office:value-type="string"><text:p>Second sheet</text:p></table:table-cell></table:table-row>
   <table:table-row><table:table-cell/><table:table-cell office:value-type="string"><text:p>Restored ODS cell</text:p></table:table-cell></table:table-row>
  </table:table>
 </office:spreadsheet></office:body>
</office:document-content>'''
manifest = '''<?xml version="1.0"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/></manifest:manifest>'''
output = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/workbook/open-document.ods'
output.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(output, 'w') as archive:
    for name, text in [('mimetype', 'application/vnd.oasis.opendocument.spreadsheet'), ('content.xml', content), ('styles.xml', '<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" office:version="1.2"><office:styles/></office:document-styles>'), ('META-INF/manifest.xml', manifest)]:
        entry = ZipInfo(name, (2026, 1, 1, 0, 0, 0)); entry.compress_type = ZIP_STORED
        archive.writestr(entry, text.encode())
print(output)
