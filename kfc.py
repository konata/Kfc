import os

from com.pnfsoftware.jeb.client.api import IScript, IClientContext
from java.io import File
from java.net import URL, URLClassLoader


class kfc(IScript):
    def run(self, ctx):
        eng = ctx.getEnginesContext()
        if eng is None:
            print("[kfc] ERROR: No engines context")
            return

        jar_path = self._find_jar()
        if jar_path is None:
            print("[kfc] ERROR: Cannot find kfc jar")
            return

        url = File(jar_path).toURI().toURL()
        loader = URLClassLoader([url], ctx.getClass().getClassLoader())
        ext_class = loader.loadClass("com.kfc.KfcExtension")
        ext = ext_class.getDeclaredConstructor().newInstance()
        ext.run(ctx)

    def _find_jar(self):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        for f in os.listdir(script_dir):
            if f.startswith("kfc-") and f.endswith(".jar"):
                return os.path.join(script_dir, f)
        build_jar = os.path.join(script_dir, "extension", "build", "libs", "kfc-0.1.0.jar")
        if os.path.isfile(build_jar):
            return build_jar
        return None
