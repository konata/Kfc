import sys
import os

from com.pnfsoftware.jeb.client.api import IScript, IClientContext
from com.pnfsoftware.jeb.core import Artifact
from com.pnfsoftware.jeb.core.input import FileInput
from java.io import File


class kfc(IScript):
    def run(self, ctx):
        args = ctx.getArguments()
        if not args:
            print("[kfc] Usage: --script=kfc.py -- /path/to/target.apk")
            return

        apk_path = args[0]
        if not os.path.isfile(apk_path):
            print("[kfc] ERROR: File not found: %s" % apk_path)
            return

        eng = ctx.getEnginesContext()
        if eng is None:
            print("[kfc] ERROR: No engines context")
            return

        # Load the APK into a project
        print("[kfc] Loading: %s" % apk_path)
        prj = eng.loadProject("KfcProject")
        artifact = Artifact(os.path.basename(apk_path), FileInput(File(apk_path)))
        prj.processArtifact(artifact)
        print("[kfc] Project loaded, units: %d" % len(self._collect_units(prj)))

        # Find and invoke KfcExtension from the jar
        jar_path = self._find_jar()
        if jar_path is None:
            print("[kfc] ERROR: Cannot find kfc jar")
            return

        from java.net import URL, URLClassLoader

        url = File(jar_path).toURI().toURL()
        loader = URLClassLoader([url], ctx.getClass().getClassLoader())
        ext_class = loader.loadClass("com.kfc.KfcExtension")
        ext = ext_class.getDeclaredConstructor().newInstance()
        # KfcExtension.run(IClientContext) will find the loaded project
        ext.run(ctx)

    def _find_jar(self):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        # Check next to this script
        for f in os.listdir(script_dir):
            if f.startswith("kfc-") and f.endswith(".jar"):
                return os.path.join(script_dir, f)
        # Check build output
        build_jar = os.path.join(script_dir, "extension", "build", "libs", "kfc-0.1.0.jar")
        if os.path.isfile(build_jar):
            return build_jar
        return None

    def _collect_units(self, prj):
        units = []
        def walk(u):
            units.append(u)
            children = u.getChildren()
            if children:
                for c in children:
                    walk(c)
        for art in prj.getLiveArtifacts():
            for u in art.getUnits():
                walk(u)
        return units
