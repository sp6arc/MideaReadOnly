#!/usr/bin/env python3
"""Reproducible Linux fallback: Kotlin 2.0.21 + SDK tools, without Gradle networking.
Requirements: Python 3, JDK 17, ANDROID_HOME with platform android-35/build-tools 35.0.0.
Downloads pinned Maven artifacts with SHA-256 verification. No account/pump required.
"""
import hashlib,json,os,pathlib,shutil,subprocess,urllib.request,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'build'/'manual';out.mkdir(parents=True,exist_ok=True)
sdk=pathlib.Path(os.environ['ANDROID_HOME'])
bt=sdk/'build-tools'/'35.0.0';android=sdk/'platforms'/'android-35'/'android.jar'
assert android.is_file(), 'Install Android SDK platform 35'
jars=out/'jars';jars.mkdir(exist_ok=True)
for item in json.loads((root/'tools'/'dependencies.json').read_text()):
    path=jars/item['filename']
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:
        with urllib.request.urlopen(item['url'],timeout=120) as response:
            payload=response.read()
        assert hashlib.sha256(payload).hexdigest()==item['sha256'], 'Dependency hash mismatch'
        path.write_bytes(payload)
def run(*args):
    subprocess.run([str(a) for a in args],check=True,cwd=root)
compiler=['java','-cp',str(jars/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17']
stdlib=jars/'kotlin-stdlib-2.0.21.jar'
sources=sorted((root/'app/src/main/java').rglob('*.kt'))
run(*compiler,'-classpath',os.pathsep.join(map(str,[android,stdlib])),'-d',out/'app.jar',*sources)
testcp=[android,stdlib,jars/'json-20240303.jar',jars/'junit-4.13.2.jar',jars/'hamcrest-core-1.3.jar']
run(*compiler,'-classpath',os.pathsep.join(map(str,testcp)),'-d',out/'tests.jar',*sources,*sorted((root/'app/src/test/java').rglob('*.kt')))
run('java','-cp',os.pathsep.join([str(out/'tests.jar'),str(jars/'*')]),'org.junit.runner.JUnitCore','pl.zuza.midea.ProtocolTest')
run(bt/'aapt2','compile','--dir',root/'app/src/main/res','-o',out/'resources.zip')
manifest=(root/'app/src/main/AndroidManifest.xml').read_text().replace('<manifest xmlns:android=', '<manifest package="pl.zuza.midea.readonly" android:versionCode="3" android:versionName="0.3.0-dashboard" xmlns:android=')
(out/'AndroidManifest.xml').write_text(manifest)
run(bt/'aapt2','link','-I',android,'--manifest',out/'AndroidManifest.xml','--min-sdk-version','26','--target-sdk-version','35','-o',out/'unsigned.apk',out/'resources.zip')
run(bt/'d8','--release','--min-api','26','--lib',android,'--output',out,out/'app.jar',stdlib)
with zipfile.ZipFile(out/'unsigned.apk','a') as archive:
    archive.write(out/'classes.dex','classes.dex')
run(bt/'zipalign','-f','-p','4',out/'unsigned.apk',out/'aligned.apk')
key=out/'test-signing.p12'
if not key.exists():
    run('keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Midea ReadOnly Test,O=Independent Development,C=PL','-keyalg','RSA','-keysize','2048','-validity','3650')
run(bt/'apksigner','sign','--ks',key,'--ks-pass','pass:android','--out',out/'MideaReadOnly-v0.3-test.apk',out/'aligned.apk')
run(bt/'apksigner','verify','--verbose',out/'MideaReadOnly-v0.3-test.apk')
print('APK:',out/'MideaReadOnly-v0.3-test.apk')
