#!/usr/bin/env python3
"""Bind original sign-in UI metadata to actual compositor attachments and clean source."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct

ROOT=Path(__file__).resolve().parents[1]
def require(value, message):
    if not value: raise ValueError(message)

def geometry(value, scene, png_pixels=None):
    """Keep UIKit point axes and the compositor's native raw pixel axes distinct."""
    def pair(key, source=value):
        items=source.get(key,[])
        require(isinstance(items,list) and len(items)==2 and all(type(n) in (int,float) and math.isfinite(n) and n>0 for n in items),'Missing actual '+key)
        return items
    def scale(key, source=value):
        number=source.get(key)
        require(type(number) in (int,float) and math.isfinite(number) and number>0,'Missing actual '+key)
        return number
    viewport=pair('viewport');points=pair('screenPoints');fixed=pair('screenFixedPoints');native=pair('screenNativePixels')
    ratio=scale('scale');native_ratio=scale('nativeScale')
    require(all(n==round(n) for n in native),'Nonintegral native pixels')
    landscape=scene.get('orientation')=='landscape'
    require(value.get('orientationMatches') is True and value.get('interfaceOrientation') in ((3,4) if landscape else (1,2)),'Missing actual interface orientation')
    require((viewport[0]>viewport[1]) is landscape,'Wrong point-space orientation')
    require(sorted(viewport)==sorted(points) and value.get('windowOnScreen')==[0,0,*points],'Reference must occupy its actual full screen')
    require(sorted(round(n*ratio) for n in viewport)==sorted(native),'Viewport/native screen geometry mismatch')
    require([round(n*native_ratio) for n in fixed]==native,'Fixed/native screen geometry mismatch')
    capture=value.get('captureImage',{})
    raw=pair('cgPixels',capture);image_size=pair('size',capture);image_scale=scale('scale',capture)
    require(type(capture.get('orientation')) is int and capture['orientation'] in range(8),'Missing native UIImage orientation')
    require(raw==native,'Compositor/native pixel mismatch')
    require(sorted(round(n*image_scale) for n in image_size)==sorted(raw),'UIImage/CGImage geometry mismatch')
    if png_pixels is not None: require(list(png_pixels)==raw,'PNG/CGImage pixel mismatch')
    return raw

def validate(value, scene, provenance, commit):
    require(value.get('referenceKind')=='signinMethods' and value.get('schema')==1 and value.get('scene')==scene['id'],'Wrong original sign-in scene')
    require(value.get('provenance')==provenance and provenance.get('projectSourceCommit')==commit and provenance.get('projectSourceDirty') is False,'Wrong/dirty sign-in source')
    require(value.get('ready') is True and value.get('categoryMatches') is True and value.get('platform')=='iOS','Missing actual UIKit readiness')
    require(value.get('style')==scene['style'] and value.get('device')==scene['device'] and value.get('mode')==scene['mode'],'Wrong actual sign-in mode/traits')
    require(value.get('swiftUIAccessibilitySize') is (scene['category']!='large') and value.get('swiftUIDirection')==('rtl' if scene['language']=='ar' else 'ltr'),'Wrong actual accessibility traits')
    require(value.get('bundleLocalizations',[None])[0]==scene['language'],'Wrong actual bundle language')
    require(value.get('fixtureAuthService') is True and value.get('sourceSessionCreated') is False,'Unexpected live auth scope')
    require(value.get('captureMethod')=='XCUIScreen.main.screenshot' and re.fullmatch('[a-f0-9]{64}',value.get('imageSha256','')),'Unbound compositor capture')
    geometry(value,scene)
    providers=value.get('providers',[])
    require([p.get('identifier') for p in providers]==['signin.apple','signin.google','signin.github'],'Missing source provider controls')
    for p in providers+[value.get('email',{}),value.get('request',{})]:
        r=p.get('frame',[]);require(len(r)==4 and all(type(n) in (int,float) and math.isfinite(n) for n in r) and r[2]>0 and r[3]>0,'Missing actual control frame')
    require(all(p.get('enabled') is (scene['mode'] not in ('google','restoring')) for p in providers),'Wrong source busy provider state')
    require(value['email'].get('identifier')=='Email' and value['email'].get('enabled') is True,'Original email field unexpectedly disabled')
    require(value['request'].get('identifier')=='signin.emailCode' and value['request'].get('enabled') is (scene['mode']=='keyboard'),'Wrong request eligibility')
    if scene['mode']=='keyboard': require(value.get('keyboardVisible') is True,'Missing actual keyboard')

def verify(exported, provenance, commit):
    require(re.fullmatch('[a-f0-9]{40}',commit or ''),'Exact capture project commit required')
    manifest=json.loads((ROOT/'third_party/cmux-signin/methods-reference.json').read_text())
    require(provenance.get('sourceFiles')==manifest['files'] and provenance.get('packageTrees')==manifest['packageTrees'] and provenance.get('upstreamRevision')==manifest['revision'],'Unreviewed originals')
    templates=ROOT/'tests/ios-signin-methods-reference'
    require(provenance.get('templates')=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in templates.iterdir() if p.is_file()},'Changed source host/tests/fixture')
    require(provenance.get('generatorSha256')==hashlib.sha256((ROOT/'scripts/prepare-ios-signin-methods-reference.py').read_bytes()).hexdigest(),'Changed source generator')
    fixture=json.loads((templates/'References.json').read_text());scenes={s['id']:s for s in fixture['scenes']};records={};images={}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size>32*1024*1024:continue
        data=path.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'):images[hashlib.sha256(data).hexdigest()]=path
        elif data.lstrip().startswith(b'{'):
            try:v=json.loads(data)
            except (ValueError,UnicodeError):continue
            if isinstance(v,dict) and v.get('referenceKind')=='signinMethods':
                require(v['scene'] not in records,'Duplicate source scene');records[v['scene']]=v
    require(set(records)==set(scenes),'Missing/unexpected original source scenes')
    paired=exported/'paired';paired.mkdir(exist_ok=True)
    for name,scene in scenes.items():
        value=records[name];validate(value,scene,provenance,commit);image=images.get(value['imageSha256']);require(image is not None,'Missing exact compositor image')
        labels=fixture['labels'][scene['language']]
        for index,key in enumerate(['apple','google','github']):
            if scene['mode']!='google' or index!=1: require(value['providers'][index]['label']==labels[key],'Wrong actual provider label')
        require(value['request']['label']==labels['emailCode'],'Wrong source request label')
        geometry(value,scene,struct.unpack('>II',image.read_bytes()[16:24]))
        shutil.copy2(image,paired/(name+'.png'));(paired/(name+'.json')).write_text(json.dumps(value,indent=2)+'\n')
    (paired/'summary.json').write_text(json.dumps(dict(scenes=len(records),projectSourceCommit=commit,scope=manifest['scope']),indent=2)+'\n')
    return len(records)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--exported',type=Path,required=True);parser.add_argument('--provenance',type=Path,required=True);parser.add_argument('--commit',default=os.environ.get('GITHUB_SHA'))
    args=parser.parse_args();print(str(verify(args.exported,json.loads(args.provenance.read_text()),args.commit))+' original sign-in compositor scenes verified')
