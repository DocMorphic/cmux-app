#!/usr/bin/env python3
"""Add original DrawingML/PNG image cases to the authored workbook fixture."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import struct
import zlib

root = Path(__file__).resolve().parents[1]
folder = root / 'app/src/androidTest/assets/workbook'
with ZipFile(folder / 'rich-runs.xlsx') as archive:
    parts = {name: archive.read(name) for name in archive.namelist()}
r = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
xdr = 'http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing'
a = 'http://schemas.openxmlformats.org/drawingml/2006/main'
rel = 'http://schemas.openxmlformats.org/package/2006/relationships'

def png():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + b''.join(bytes((226, 163, 74) if x < 32 else (36, 80, 128)) for x in range(64)) for _ in range(32))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 32, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')

def marker(tag, col, row, x=0, y=0):
    return f'<xdr:{tag}><xdr:col>{col}</xdr:col><xdr:colOff>{x}</xdr:colOff><xdr:row>{row}</xdr:row><xdr:rowOff>{y}</xdr:rowOff></xdr:{tag}>'

def picture(id, name, image='photo', extra='', transform=''):
    return f'''<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="{id}" name="{name}" descr="{name}"/><xdr:cNvPicPr/></xdr:nvPicPr>
    <xdr:blipFill><a:blip r:embed="{image}"/>{extra}<a:stretch><a:fillRect/></a:stretch></xdr:blipFill>
    <xdr:spPr><a:xfrm {transform}/><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>'''

def drawing(anchors):
    return f'<xdr:wsDr xmlns:xdr="{xdr}" xmlns:a="{a}" xmlns:r="{r}">{anchors}</xdr:wsDr>'.encode()

def one(id, name, col, row, width=160, height=80, **kwargs):
    return '<xdr:oneCellAnchor>' + marker('from', col, row, 95250, 47625) + picture(id, name, **kwargs) + f'<xdr:ext cx="{width*9525}" cy="{height*9525}"/><xdr:clientData/></xdr:oneCellAnchor>'

anchors = one(1, 'Cropped flipped picture', 3, 15, extra='<a:srcRect l="25000"/>', transform='flipH="1"')
anchors += '<xdr:twoCellAnchor>' + marker('from', 0, 18) + marker('to', 2, 21) + picture(2, 'Cell anchored picture') + '<xdr:clientData/></xdr:twoCellAnchor>'
anchors += one(3, 'Far page picture', 0, 130, width=200, height=100)
anchors += '<xdr:absoluteAnchor><xdr:pos x="1524000" y="5334000"/>' + picture(4, 'Rotated absolute picture', transform='rot="5400000"') + '<xdr:ext cx="1143000" cy="571500"/><xdr:clientData/></xdr:absoluteAnchor>'
anchors += one(5, 'External picture blocked', 0, 0, image='external')
parts['xl/drawings/drawing1.xml'] = drawing(anchors)
parts['xl/drawings/drawing2.xml'] = drawing(one(6, 'Image only worksheet', 1, 11, width=120, height=60))
parts['xl/media/photo one.png'] = png()
for n in [1, 2]:
    parts[f'xl/drawings/_rels/drawing{n}.xml.rels'] = f'<Relationships xmlns="{rel}"><Relationship Id="photo" Type="{r}/image" Target="../media/photo%20one.png"/><Relationship Id="external" Type="{r}/image" Target="https://example.invalid/image.png" TargetMode="External"/></Relationships>'.encode()
parts['xl/worksheets/sheet1.xml'] = parts['xl/worksheets/sheet1.xml'].replace(b'</worksheet>', f'<drawing xmlns:r="{r}" r:id="pictures"/></worksheet>'.encode())
parts['xl/worksheets/_rels/sheet1.xml.rels'] = parts['xl/worksheets/_rels/sheet1.xml.rels'].replace(b'</Relationships>', f'<Relationship Id="pictures" Type="{r}/drawing" Target="../drawings/drawing1.xml"/></Relationships>'.encode())
parts['xl/worksheets/sheet4.xml'] = f'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/><drawing xmlns:r="{r}" r:id="pictures"/></worksheet>'.encode()
parts['xl/worksheets/_rels/sheet4.xml.rels'] = f'<Relationships xmlns="{rel}"><Relationship Id="pictures" Type="{r}/drawing" Target="../drawings/drawing2.xml"/></Relationships>'.encode()
parts['xl/workbook.xml'] = parts['xl/workbook.xml'].replace(b'</sheets>', f'<sheet name="Pictures only" sheetId="4" xmlns:r="{r}" r:id="rId4"/></sheets>'.encode())
parts['xl/_rels/workbook.xml.rels'] = parts['xl/_rels/workbook.xml.rels'].replace(b'</Relationships>', f'<Relationship Id="rId4" Type="{r}/worksheet" Target="worksheets/sheet4.xml"/></Relationships>'.encode())
content_types = parts['[Content_Types].xml'].decode().replace('</Types>', '<Default Extension="png" ContentType="image/png"/>' + ''.join(f'<Override PartName="/xl/drawings/drawing{n}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' for n in [1, 2]) + '<Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
parts['[Content_Types].xml'] = content_types.encode()
with ZipFile(folder / 'pictures.xlsx', 'w') as archive:
    for name, data in parts.items():
        info = ZipInfo(name, (2026, 10, 10, 0, 0, 0)); info.compress_type = ZIP_DEFLATED
        info.external_attr = 0o600 << 16; archive.writestr(info, data)
print('Generated original workbook picture fixture.')
