import os

from com.pnfsoftware.jeb.client.api import IScript
from java.io import File
from java.net import URLClassLoader


class kfc(IScript):
    entry = "kfc.mcp.Extension"

    def run(self, ctx):
        jar = self.find_jar()
        if ctx.getEnginesContext() is None:
            print("[kfc] ERROR: No engines context")
            return
        if jar is None:
            print("[kfc] ERROR: Cannot find kfc jar")
            return

        loader = URLClassLoader([File(jar).toURI().toURL()], ctx.getClass().getClassLoader())
        loader.loadClass(self.entry).getDeclaredConstructor().newInstance().run(ctx)

    def find_jar(self):
        here = os.path.dirname(os.path.abspath(__file__))
        for name in os.listdir(here):
            if name.startswith("kfc-") and name.endswith(".jar"):
                return os.path.join(here, name)

        jar = os.path.join(here, "extension", "build", "libs", "kfc-0.1.0.jar")
        return jar if os.path.isfile(jar) else None
