#!/usr/bin/env python3
"""Run the opt-in production preference probe; this is not UI or inference acceptance."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,time,uuid
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description="Verify the real translation preference factory across cold app processes on an owned disposable AVD. Build both debug APKs first and stop the project's Gradle process before running.")
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--sdk',type=Path,default=Path.home()/'Library/Android/sdk')
parser.add_argument('--avd',default='cmux_parity')
args=parser.parse_args()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
sdk=args.sdk.resolve();adb=[str(sdk/'platform-tools/adb'),'-s','emulator-5554'];seed=uuid.uuid4().hex
assert len(subprocess.check_output([str(sdk/'platform-tools/adb'),'devices'],text=True).strip().splitlines())==1,'Inspect existing devices'
log=(out/'emulator.log').open('w');proc=subprocess.Popen([str(sdk/'emulator/emulator'),'-avd',args.avd,'-port','5554','-no-window','-no-snapshot','-no-audio','-gpu','swiftshader','-memory','2048'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
print('Owned emulator PID',proc.pid,flush=True)
def cmd(args,timeout=60):return subprocess.run(adb+args,check=True,capture_output=True,timeout=timeout)
installed=False;cleanup_error=None
try:
 for _ in range(90):
  if proc.poll() is not None:raise RuntimeError('Owned emulator exited')
  try:
   if cmd(['shell','getprop','sys.boot_completed'],timeout=3).stdout.strip()==b'1':break
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
  time.sleep(1)
 else:raise RuntimeError('Owned emulator did not boot')
 assert args.avd in cmd(['emu','avd','name']).stdout.decode().splitlines()
 sha=lambda p:hashlib.sha256((root/Path(p)).read_bytes()).hexdigest()
 paths=['app/src/androidTest/java/io/github/docmorphic/cmuxapp/NativeChiefTranslationPersistenceProbeTest.kt','app/src/main/java/io/github/docmorphic/cmuxapp/NativeChiefTranslationPlatformHost.kt','app/src/main/java/io/github/docmorphic/cmuxapp/NativeChiefTranslationPreferences.kt','app/src/main/java/io/github/docmorphic/cmuxapp/NativeChiefTranslations.kt','app/src/main/java/io/github/docmorphic/cmuxapp/NativeChiefSession.kt',str(Path(__file__).resolve().relative_to(root))]
 receipt=dict(baseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),run=seed,sourceSha256={p:sha(p) for p in paths},appApkSha256=sha('app/build/outputs/apk/debug/app-debug.apk'),testApkSha256=sha('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),scope='Actual native production factory and disk preferences, separate instrumentation processes with forced cold restart. Empty transcript/no message text/RPC/model download/settings UI. Not UI lifecycle, actual inference, physical device or full parity.')
 (out/'inputs.json').write_text(json.dumps(receipt,indent=2)+'\n')
 for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:cmd(['install','-r',str(root/apk)])
 installed=True;cmd(['logcat','-b','crash','-c'])
 for phase in ['write','read','cleanup']:
  cmd(['shell','rm','-f','/sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/screenshots/chief-translation-persistence-'+phase+'.json'])
 for phase in ['write','read']:
  cmd(['shell','am','force-stop','io.github.docmorphic.cmuxapp.debug'])
  r=cmd(['shell','am','instrument','-w','-r','-e','class','io.github.docmorphic.cmuxapp.NativeChiefTranslationPersistenceProbeTest','-e','chiefTranslationPersistenceRun',seed,'-e','chiefTranslationPersistencePhase',phase,'io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner'],timeout=90)
  data=r.stdout+r.stderr;(out/(phase+'.log')).write_bytes(data);print(data.decode(errors='replace')[-4500:],flush=True)
  assert b'OK (1 test)' in data and b'FAILURES!!!' not in data,'Inspect '+phase+' runtime'
  cmd(['pull','/sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/screenshots/chief-translation-persistence-'+phase+'.json',str(out/(phase+'.json'))])
 (out/'crash.log').write_bytes(cmd(['logcat','-b','crash','-d'],timeout=20).stdout)
 write=json.loads((out/'write.json').read_text());read=json.loads((out/'read.json').read_text())
 assert write['run']==read['run']==seed and write['pid']!=read['pid']
 assert read['coldRestore'] and read['stopRemovedDiskKey'] and read['unrelatedPreferencesPreserved'] and read['sentinelColdRestoreAndPreservation']
 assert (out/'crash.log').stat().st_size==0,'Inspect crash buffer'
 print('Verified actual production preference persistence across distinct app processes',write['pid'],read['pid'],flush=True)
finally:
 if proc.poll() is None:
  if installed:
   try:
    cmd(['shell','am','force-stop','io.github.docmorphic.cmuxapp.debug'])
    r=cmd(['shell','am','instrument','-w','-r','-e','class','io.github.docmorphic.cmuxapp.NativeChiefTranslationPersistenceProbeTest','-e','chiefTranslationPersistenceRun',seed,'-e','chiefTranslationPersistencePhase','cleanup','io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner'],timeout=90)
    (out/'cleanup.log').write_bytes(r.stdout+r.stderr)
    assert b'OK (1 test)' in r.stdout+r.stderr,'Cleanup test failed'
    cmd(['pull','/sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/screenshots/chief-translation-persistence-cleanup.json',str(out/'cleanup.json')])
   except Exception as error:
    cleanup_error=error;print('Probe cleanup diagnostic',type(error).__name__,str(error),flush=True)
  for args in [['shell','am','force-stop','io.github.docmorphic.cmuxapp.debug'],['emu','kill']]:
   try:cmd(args,timeout=15)
   except Exception as error:print('Emulator cleanup diagnostic',type(error).__name__,flush=True)
  try:proc.wait(timeout=30)
  except subprocess.TimeoutExpired:proc.terminate();proc.wait(timeout=10)
 log.close();print('Owned emulator terminal',proc.poll(),flush=True)
 if cleanup_error is not None:raise RuntimeError('Probe cleanup failed; inspect cleanup.log') from cleanup_error

cleanup=json.loads((out/'cleanup.json').read_text())
assert cleanup['run']==seed and cleanup['probeDiskKeysRemoved'] and cleanup['backendCalls']==0
phases={phase:dict(json.loads((out/(phase+'.json')).read_text()),seconds=float(re.search(r'Time: ([0-9.]+)',(out/(phase+'.log')).read_text()).group(1))) for phase in ['write','read','cleanup']}
verification=dict(inputs=receipt,phases=phases,passes=3,skips=0,failures=0,crashBytes=0,ownedEmulatorStopped=proc.poll() is not None)
(out/'verification.json').write_text(json.dumps(verification,indent=2)+'\n')
print('Cold-process preference verification passed; cleanup and emulator exit verified.',flush=True)
