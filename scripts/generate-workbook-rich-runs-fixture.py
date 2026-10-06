#!/usr/bin/env python3
"""Add generated OOXML rich-text cases to the existing authored workbook fixture."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
folder = root / 'app/src/androidTest/assets/workbook'
with ZipFile(folder / 'rich.xlsx') as archive:
    parts = {name: archive.read(name) for name in archive.namelist()}
rows = '''<row r="11" ht="34" customHeight="1"><c r="A11" t="inlineStr"><is>
<r><rPr><b/><color rgb="FFFF0000"/><sz val="24"/></rPr><t>RED</t></r>
<r><rPr><i/><color rgb="FF008000"/><sz val="24"/></rPr><t xml:space="preserve"> green</t></r>
</is></c></row>
<row r="12" ht="30" customHeight="1"><c r="A12" t="s"><v>0</v></c></row>
<row r="13" ht="26" customHeight="1"><c r="A13" t="inlineStr" s="1"><is>
<r><rPr><b val="0"/><i val="false"/><u val="none"/><strike val="0"/></rPr><t>plain</t></r>
<r><rPr><u val="double"/><strike/></rPr><t xml:space="preserve"> decorated</t></r>
</is></c></row>
<row r="14"><c r="A14" t="s"><v>1</v></c></row>'''
sheet = parts['xl/worksheets/sheet1.xml'].decode().replace('<row r="205">', rows + '<row r="205">')
sheet = sheet.replace('<hyperlinks>', '<hyperlinks><hyperlink ref="A12" location="Details!B2"/>')
sheet = sheet.replace('<mergeCells count="1">', '<mergeCells count="5"><mergeCell ref="A11:D11"/><mergeCell ref="A12:D12"/><mergeCell ref="A13:D13"/><mergeCell ref="A14:D14"/>')
parts['xl/worksheets/sheet1.xml'] = sheet.encode()
parts['xl/sharedStrings.xml'] = b'''<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
<si><r><t>H</t></r><r><rPr><vertAlign val="subscript"/></rPr><t>2</t></r><r><t>O + x</t></r>
<r><rPr><vertAlign val="superscript"/></rPr><t>2</t></r><r><t xml:space="preserve"> &lt;script&gt;unsafe&lt;/script&gt;</t></r></si>
<si><r><rPr><b/></rPr><t>_x005F_x0041_</t></r><r><t xml:space="preserve"> literal</t></r></si></sst>'''
parts['[Content_Types].xml'] = parts['[Content_Types].xml'].replace(b'</Types>', b'<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>')
parts['xl/_rels/workbook.xml.rels'] = parts['xl/_rels/workbook.xml.rels'].replace(b'</Relationships>', b'<Relationship Id="richStrings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/></Relationships>')
with ZipFile(folder / 'rich-runs.xlsx', 'w') as archive:
    for name, content in parts.items():
        info = ZipInfo(name, (2026, 10, 7, 0, 0, 0))
        info.compress_type = ZIP_DEFLATED
        info.external_attr = 0o600 << 16
        archive.writestr(info, content)
