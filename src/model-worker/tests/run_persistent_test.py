"""Exclusive GPU ownership around the full-composition native Job Object regression."""
import json,os,pathlib,subprocess
project=pathlib.Path(__file__).resolve().parents[3];research=project/"src/build/research-20261024";lock=research/"gpu.lock"
owner={"pid":os.getpid(),"owner":"gpu-worker-persistence","task":"full composition real Job Object 16k v2 reuse regression"}
fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY);os.write(fd,json.dumps(owner).encode());os.close(fd)
try:
    java=project/"src/build/tools/graalvm-community-openjdk-25.0.2+10.1/bin/java.exe"
    cp=";".join(str(project/p) for p in ["src/build/research-20261024/persistent-classes","src/build/classes/java/main","src/build/resources/main","src/build/runtime-libs/*"])
    subprocess.run([str(java),"--enable-native-access=ALL-UNNAMED","-Dfile.encoding=UTF-8","-cp",cp,"ai.enterprise.tests.PersistentGenerationTest",str(project),str(research/"gpu-worker/persistent-package/model"),str(research/"gpu-worker/persistent-test")],check=True)
finally:
    if json.loads(lock.read_text())["pid"]==os.getpid():lock.unlink()
