#!/usr/bin/env python3
"""Verify complete pinned UIKit translation UI references; synthetic guard tests never establish native evidence."""
import argparse, binascii, hashlib, json, math, shutil, struct
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def require(value, message):
    if not value: raise ValueError(message)
def rect(value):
    return isinstance(value,list) and len(value)==4 and all(type(v) in (int,float) and math.isfinite(v) for v in value) and min(value[2:])>0

def exif_orientation(data):
    # PNG eXIf contains a TIFF header. Only the unmirrored orientations emitted
    # by XCUIScreen are supported; an absent orientation means upright.
    require(len(data)>=8 and data[:2] in (b'II',b'MM'),'Invalid compositor EXIF header')
    endian='<' if data[:2]==b'II' else '>'
    require(struct.unpack(endian+'H',data[2:4])[0]==42,'Invalid compositor TIFF marker')
    offset=struct.unpack(endian+'I',data[4:8])[0]
    require(offset>=8 and offset+2<=len(data),'Invalid compositor EXIF directory')
    count=struct.unpack(endian+'H',data[offset:offset+2])[0]
    require(offset+2+12*count+4<=len(data),'Truncated compositor EXIF directory')
    orientation=None
    for index in range(count):
        entry=data[offset+2+12*index:offset+14+12*index]
        tag,kind,items=struct.unpack(endian+'HHI',entry[:8])
        if tag!=274: continue
        require(orientation is None and kind==3 and items==1,'Invalid/duplicate compositor orientation')
        orientation=struct.unpack(endian+'H',entry[8:10])[0]
        require(orientation in (1,3,6,8),'Unsupported mirrored compositor orientation')
    return orientation or 1

def png_geometry(data):
    require(data.startswith(b'\x89PNG\r\n\x1a\n'),'Invalid compositor PNG signature')
    offset=8;size=None;orientation=1;has_exif=False;has_pixels=False;ended=False
    while offset<len(data):
        require(offset+12<=len(data),'Truncated compositor PNG chunk')
        length=struct.unpack('>I',data[offset:offset+4])[0]
        require(offset+length+12<=len(data),'Truncated compositor PNG payload')
        kind=data[offset+4:offset+8];payload=data[offset+8:offset+8+length]
        crc=struct.unpack('>I',data[offset+8+length:offset+12+length])[0]
        require(binascii.crc32(kind+payload)&0xffffffff==crc,'Changed compositor PNG chunk checksum')
        if kind==b'IHDR':
            require(offset==8 and length==13 and size is None,'Invalid compositor image header')
            size=list(struct.unpack('>II',payload[:8]));require(min(size)>0,'Invalid compositor image size')
        elif kind==b'eXIf':
            require(not has_exif,'Duplicate compositor EXIF chunk')
            has_exif=True;orientation=exif_orientation(payload)
        elif kind==b'IDAT':
            has_pixels=has_pixels or bool(payload)
        elif kind==b'IEND':
            require(length==0 and offset+12==len(data),'Invalid compositor PNG end')
            ended=True;break
        offset+=length+12
    require(size is not None and has_pixels and ended,'Incomplete compositor PNG')
    return dict(encodedSize=size,exifOrientation=orientation,displaySize=size[::-1] if orientation in (6,8) else size)

def verify(exported, output, commit):
    require(len(commit)==40 and all(c in '0123456789abcdef' for c in commit), 'Exact project commit required')
    template=ROOT/'tests/ios-chief-reference';manifest=json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text())
    fixture=json.loads((template/'TranslationReferences.json').read_text());scenes={v['id']:v for v in fixture['scenes']};rows={v['id']:v for v in fixture['rows']}
    values={};images={}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size>32*1024*1024: continue
        data=path.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'): images[hashlib.sha256(data).hexdigest()]=path
        elif data.lstrip().startswith(b'{'):
            try: value=json.loads(data)
            except (ValueError,UnicodeError): continue
            if isinstance(value,dict) and 'translationScene' in value:
                name=value['translationScene'];require(name not in values,'Duplicate translation scene');values[name]=(path,value)
    require(set(values)==set(scenes),'Missing/unexpected translation scenes')
    paired=[]
    for name,spec in scenes.items():
        path,v=values[name];p=v['provenance'];target=rows[spec['target']]
        require(v.get('fixture')==spec and v.get('scene')==name,'Wrong translation fixture')
        require(v.get('captureMethod')=='XCUIScreen.main.screenshot' and v.get('activation')=='physicalPressAndSourceControls','Missing compositor/physical source activation')
        require(v.get('ready') is True and v.get('categoryMatches') is True and v.get('style')==spec['style'] and v.get('direction')==spec['direction'],'Wrong actual translation traits')
        require(v.get('translator')=='controlled' and v.get('backendWriteAttempts')==0,'Unproven translator/backend scope')
        require(p.get('projectSourceCommit')==commit and p.get('projectSourceDirty') is False,'Wrong/dirty translation source')
        require(p.get('upstreamRevision')==manifest['revision'] and p.get('packageTree')==manifest['referencePackage']['tree'],'Unreviewed translation package')
        require(p.get('referenceAppSha256')==digest(template/'TranslationReferenceApp.swift') and p.get('translationUiTestsSha256')==digest(template/'TranslationUITests.swift'),'Translation templates changed')
        require(v.get('fixtureSha256')==p.get('translationFixtureSha256')==digest(template/'TranslationReferences.json'),'Translation fixture changed')
        size=v.get('viewport');scale=v.get('scale')
        require(isinstance(size,list) and len(size)==2 and all(type(x) in (int,float) and math.isfinite(x) and x>0 for x in size) and type(scale) in (int,float) and math.isfinite(scale) and scale>0,'Invalid translation viewport')
        require((size[0]>size[1])==(spec['orientation']=='landscape'),'Wrong translation orientation')
        require(rect(v.get('controllerFrame')) and all(abs(a-b)<.1 for a,b in zip(v['controllerFrame'],[0,0,*size])),'Source controller does not fill capture viewport')
        labels=v.get('labels',[]);controls=v.get('controls',[])
        require(labels and all(rect(x.get('frame')) and x.get('fontSize',0)>0 for x in labels) and all(rect(x.get('frame')) for x in controls),'Missing translation geometry')
        if spec['category']=='accessibilityMedium':
            body=[x for x in labels if x.get('class')=='ConversationEffectLabel'];require(body and all(x['fontSize']>=28 for x in body),'Actual accessibility body type missing')
        presentations={x['id']:x for x in v['presentations']};require(set(presentations)==set(rows),'Wrong source transcript')
        for key,row in rows.items():
            original=fixture['sameLanguageText'] if spec['mode']=='from' and key==spec['target'] else row['text']
            require(presentations[key]['original']==original,'Source original was changed')
        mode=spec['mode'];current=presentations[spec['target']]
        if mode in ('choice','from'):
            require(v['translationCalls']==0 and current['caption']=='none','Translation ran before admission')
            expected=['Translate This Message','Translate Conversation','Cancel'] if mode=='choice' else ['French','Spanish','Cancel']
            require(v['alertActions']==expected,'Wrong native translation action sheet')
            if mode=='from': require(v['alertTitle']=='Translate From','Missing source chooser title')
        else:
            require(v['translationCalls']>=1,'No controlled translation executed')
            require(current['caption']==('viewTranslation' if mode=='original' else 'showOriginal'),'Wrong source caption state')
            require(current['shown']==(target['text'] if mode=='original' else target['translated']),'Wrong shown source text')
            if mode=='menu':
                require(any(x.get('identifier')=='conversation.menu.translate' and x.get('label')=='Show Original' for x in controls),'Wrong translated menu action')
            else: require(any(x.get('identifier')=='conversation.message.translation' for x in labels),'Missing visible translation caption')
        require(v['automatic']==(mode=='conversation'),'Wrong source automatic translation')
        if mode=='conversation':
            require(all(presentations[k]['shown']==(r['text'] if r['outgoing'] else r['translated']) for k,r in rows.items()),'Automatic translation covered wrong senders')
            indicator=next((x for x in controls if x.get('identifier')=='conversation.translation.indicator'),None)
            require(indicator is not None and abs(indicator['frame'][3]-32)<.1,'Wrong source indicator')
        image=images.get(v['imageSha256']);require(image is not None,'Missing/changed compositor screenshot')
        geometry=png_geometry(image.read_bytes())
        require(geometry['displaySize']==[round(x*scale) for x in size],'Wrong compositor image dimensions')
        paired.append((name,path,image,v,geometry))
    output.mkdir(parents=True,exist_ok=True)
    for name,path,image,value,geometry in paired:
        shutil.copyfile(image,output/(name+'.png'));(output/(name+'.json')).write_text(json.dumps(value,indent=2)+'\n')
    receipt=dict(projectCommit=commit,verifierSha256=digest(Path(__file__)),revision=manifest['revision'],packageTree=manifest['referencePackage']['tree'],scenes=list(scenes),captureMethod='XCUIScreen.main.screenshot',translator='controlled',imageGeometry={name:geometry for name,path,image,value,geometry in paired},scope='Pinned UIKit translation UI, physical gestures/source actions, actual traits/frames and compositor images. Original PNG bytes and EXIF retained; display dimensions include validated orientation. Not model inference, Android comparison, physical-device or VoiceOver acceptance.')
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--exported',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--project-commit',required=True);a=p.parse_args();verify(a.exported,a.output,a.project_commit)
