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

# A second authored fixture exercises presentation metadata rather than altering the data fixture.
styled = content.replace('xmlns:number=', 'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:number=', 1)
styled = styled.replace('<office:automatic-styles>', '''<office:automatic-styles>
 <style:style style:name="heading" style:family="table-cell" style:parent-style-name="base"><style:table-cell-properties fo:background-color="#17365D" fo:border="1pt solid #FFFFFF"/><style:paragraph-properties fo:text-align="center"/></style:style>
 <style:style style:name="wide" style:family="table-column"><style:table-column-properties style:column-width="2in"/></style:style>
 <style:style style:name="numeric" style:family="table-column"><style:table-column-properties style:column-width="1.5in"/></style:style>
 <style:style style:name="tall" style:family="table-row"><style:table-row-properties style:row-height="36pt"/></style:style>
 <style:style style:name="footer" style:family="table-cell"><style:text-properties fo:font-weight="bold" fo:color="#005500"/><style:table-cell-properties fo:background-color="#CCFFCC"/></style:style>
 <style:style style:name="hiddenChild" style:family="table" style:parent-style-name="hiddenBase"/>
''', 1)
styled = styled.replace('<table:table table:name="Résumé">', '''<table:table table:name="Résumé">
 <table:table-column table:style-name="wide"/><table:table-column table:style-name="numeric"/><table:table-column table:visibility="collapse"/>''', 1)
styled = styled.replace('<table:table-row><table:table-cell office:value-type="string" table:number-columns-spanned="2">', '<table:table-row table:style-name="tall"><table:table-cell table:style-name="heading" office:value-type="string" table:number-columns-spanned="2">', 1)
styled = styled.replace('<text:p>$1,234.50</text:p></table:table-cell></table:table-row>', '<text:p>$1,234.50</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p>Hidden column</text:p></table:table-cell></table:table-row>', 1)
styled = styled.replace('table:number-rows-repeated="3"', 'table:number-rows-repeated="3" table:visibility="filter"', 1)
styled = styled.replace('  </table:table>\n  <table:table table:name="Details">', '''   <table:table-row-group table:display="false"><table:table-row><table:table-cell office:value-type="string"><text:p>Hidden group</text:p></table:table-cell></table:table-row></table:table-row-group>
   <table:table-row table:default-cell-style-name="footer"><table:table-cell office:value-type="string"><text:p>Visible footer</text:p></table:table-cell></table:table-row>
  </table:table>
  <table:table table:name="Details">''', 1)
styled = styled.replace(' </office:spreadsheet>', ''' <table:table table:name="Hidden" table:style-name="hiddenChild"><table:table-row><table:table-cell office:value-type="string"><text:p>Hidden sheet</text:p></table:table-cell></table:table-row></table:table>
 </office:spreadsheet>''', 1)
styles = '''<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" office:version="1.2"><office:styles>
<style:default-style style:family="table-cell"><style:text-properties fo:font-size="12pt"/></style:default-style>
<style:style style:name="base" style:family="table-cell"><style:text-properties fo:font-size="16pt" fo:font-weight="bold" fo:color="#FFFFFF"/></style:style>
<style:style style:name="hiddenBase" style:family="table"><style:table-properties table:display="false"/></style:style>
</office:styles></office:document-styles>'''
output = output.with_name('open-document-styled.ods')
with ZipFile(output, 'w') as archive:
    for name, text in [('mimetype', 'application/vnd.oasis.opendocument.spreadsheet'), ('content.xml', styled), ('styles.xml', styles), ('META-INF/manifest.xml', manifest.replace('</manifest:manifest>', '<manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/></manifest:manifest>'))]:
        entry = ZipInfo(name, (2026, 1, 1, 0, 0, 0)); entry.compress_type = ZIP_STORED
        archive.writestr(entry, text.encode())
print(output)

rich = styled.split(' <office:body>')[0].replace('</office:automatic-styles>', '''
 <style:style style:name="boldRun" style:family="text"><style:text-properties fo:font-weight="bold" fo:color="#C00000"/></style:style>
 <style:style style:name="italicRun" style:family="text"><style:text-properties fo:font-style="italic"/></style:style>
 <style:style style:name="plainRun" style:family="text"><style:text-properties fo:font-weight="normal" fo:color="#000000"/></style:style>
 <style:style style:name="wrapped" style:family="table-cell"><style:table-cell-properties fo:wrap-option="wrap"/></style:style>
 </office:automatic-styles>''') + '''<office:body><office:spreadsheet>
 <table:table table:name="Rich">
 <table:table-column table:style-name="wide" table:default-cell-style-name="wrapped"/>
 <table:table-row><table:table-cell office:value-type="string"><text:p>Rich ODS 日本語</text:p></table:table-cell></table:table-row>
 <table:table-row><table:table-cell office:value-type="string"><text:p>Normal <text:span text:style-name="boldRun">bold <text:span text:style-name="italicRun">both</text:span><text:span text:style-name="plainRun"> plain</text:span></text:span> end</text:p></table:table-cell></table:table-row>
 <table:table-row><table:table-cell office:value-type="string"><text:p><text:a xlink:href="#Details.B2">Inside</text:a> + <text:a xlink:href="https://example.com">Outside</text:a> <text:a xlink:href="javascript:window.__unsafeOds=true">Blocked</text:a></text:p></table:table-cell></table:table-row>
 <table:table-row><table:table-cell office:value-type="string"><text:p>A<text:s text:c="3"/>B<text:tab/>C<text:line-break/>D</text:p><text:p>日本語 &lt;script&gt;literal&lt;/script&gt;</text:p></table:table-cell></table:table-row>
 </table:table>
 <table:table table:name="Details"><table:table-row/><table:table-row><table:table-cell/><table:table-cell office:value-type="string"><text:p>Rich link target</text:p></table:table-cell></table:table-row></table:table>
 </office:spreadsheet></office:body></office:document-content>'''
output = output.with_name('open-document-rich.ods')
with ZipFile(output, 'w') as archive:
    for name, text in [('mimetype', 'application/vnd.oasis.opendocument.spreadsheet'), ('content.xml', rich), ('styles.xml', styles), ('META-INF/manifest.xml', manifest.replace('</manifest:manifest>', '<manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/></manifest:manifest>'))]:
        entry = ZipInfo(name, (2026, 1, 1, 0, 0, 0)); entry.compress_type = ZIP_STORED
        archive.writestr(entry, text.encode())
print(output)
