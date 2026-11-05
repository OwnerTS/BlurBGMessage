import os
import shutil
import hashlib
import traceback

from base_plugin import BasePlugin
from android_utils import log
from elyx import strings
from org.telegram.messenger import ApplicationLoader, FileLog
from dalvik.system import DexClassLoader
from java.io import File
from java.util import HashMap
from java.lang import Object
from java import jclass

try:
    from elyx import mvel_execute
except Exception:
    mvel_execute = None

try:
    from org.mvel2 import MVEL
except Exception:
    MVEL = None


def execute_java_script(script: str, variables: dict):
    if mvel_execute:
        try:
            return mvel_execute(script, variables)
        except Exception as e:
            log(f"[BlurBGMessage] elyx.mvel_execute notice: {e}, falling back to MVEL.eval")

    if MVEL:
        jmap = HashMap()
        for k, v in variables.items():
            jmap.put(k, v)
        return MVEL.eval(script, jmap)

    raise RuntimeError("Neither elyx.mvel_execute nor org.mvel2.MVEL is available")


class BlurBGMessagePlugin(BasePlugin):
    def __init__(self):
        super().__init__()
        self.ctx = None
        self.java_holder = HashMap()

    def on_plugin_load(self):
        try:
            from android.util import Log
            Log.e("BlurBGMessage", ">>> [Python] BlurBGMessagePlugin.on_plugin_load starting <<<")
            self.logger.info(">>> [BlurBGMessage] on_plugin_load starting <<<")
            log(">>> [BlurBGMessage] on_plugin_load starting <<<")

            self.ctx = ApplicationLoader.applicationContext
            if not self.ctx:
                log("[BlurBGMessage] ApplicationContext is null, aborting")
                return

            self._do_unload()

            # 1. Resolve bundled DEX
            bundled_dex = None
            try:
                from elyx import assets
                asset = assets.get("dex/classes.dex")
                if asset and hasattr(asset, "path"):
                    bundled_dex = str(asset.path)
            except Exception as e:
                log(f"[BlurBGMessage] assets.get notice: {e}")

            if not bundled_dex or not os.path.exists(bundled_dex):
                cands = [
                    os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "res", "dex", "classes.dex")),
                    os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "res", "classes.dex")),
                    os.path.abspath(os.path.join(os.path.dirname(__file__), "classes.dex")),
                    os.path.abspath(os.path.join(os.path.dirname(__file__), "res", "dex", "classes.dex")),
                ]
                for c in cands:
                    if os.path.exists(c):
                        bundled_dex = c
                        break

            if not bundled_dex or not os.path.exists(bundled_dex):
                raise FileNotFoundError(f"classes.dex not found near {__file__}")

            log(f"[BlurBGMessage] Found bundled DEX at: {bundled_dex}")
            self.logger.info(f"[BlurBGMessage] Found bundled DEX at: {bundled_dex}")

            # 2. Deploy to isolated directory with 0444 read-only permissions
            dest_dir = File(self.ctx.getFilesDir(), "blurbgmessage_plugin")
            if not dest_dir.exists():
                dest_dir.mkdirs()

            dest_file = File(dest_dir, "classes.dex")
            dest_path = dest_file.getAbsolutePath()

            def file_md5(p):
                try:
                    with open(p, "rb") as f:
                        return hashlib.md5(f.read()).hexdigest()
                except Exception:
                    return ""

            need_copy = not dest_file.exists() or (file_md5(bundled_dex) != file_md5(dest_path))
            if need_copy:
                if dest_file.exists():
                    try:
                        dest_file.setWritable(True, True)
                    except Exception:
                        pass
                    try:
                        dest_file.delete()
                    except Exception:
                        pass
                shutil.copyfile(bundled_dex, dest_path)
                log(f"[BlurBGMessage] Deployed fresh DEX to {dest_path}")
                self.logger.info(f"[BlurBGMessage] Deployed fresh DEX to {dest_path}")

            try:
                dest_file.setWritable(False, False)
                dest_file.setReadOnly()
            except Exception as e:
                log(f"[BlurBGMessage] setReadOnly notice: {e}")

            # 3. Create DexClassLoader
            opt_dir = self.ctx.getDir("dex_opt", 0).getAbsolutePath()
            loader = DexClassLoader(dest_path, opt_dir, None, self.ctx.getClassLoader())
            log(f"[BlurBGMessage] DexClassLoader initialized for {dest_path}")
            self.logger.info(f"[BlurBGMessage] DexClassLoader initialized")

            # 4. Instantiate and invoke onLoad inside JVM via MVEL
            bridge = Object()
            vars = {
                "loader": loader,
                "ctx": self.ctx,
                "bridge": bridge,
                "holder": self.java_holder,
                "object_cls": jclass("java.lang.Object"),
            }

            load_script = """
Class cls = loader.loadClass("com.swaga.blurbgmessage.BlurHookBridge");
Object inst = cls.newInstance();
cls.getMethod("onLoad", object_cls, object_cls).invoke(inst, ctx, bridge);
holder.put("instance", inst);
return "SUCCESS";
"""
            res = execute_java_script(load_script, vars)
            log(f"[BlurBGMessage] Module initialized successfully via JVM: {res}")
            self.logger.info(f"[BlurBGMessage] Loaded and active: {res}")

        except Exception as e:
            err = traceback.format_exc()
            log(f"[BlurBGMessage] Critical error in on_plugin_load:\n{err}")
            self.logger.error(err)
            try:
                from android.util import Log
                Log.e("BlurBGMessage", f"[Python] Critical error:\n{err}")
            except Exception:
                pass
            raise e

    def on_plugin_unload(self):
        self._do_unload()

    def _do_unload(self):
        try:
            if self.java_holder and self.java_holder.containsKey("instance"):
                unload_script = """
Object inst = holder.get("instance");
if (inst != null) {
    inst.onUnload();
    holder.clear();
}
return "SUCCESS";
"""
                vars = {"holder": self.java_holder}
                execute_java_script(unload_script, vars)
                log("[BlurBGMessage] Module unloaded successfully.")
                self.logger.info("[BlurBGMessage] Module unloaded successfully.")
        except Exception as e:
            log(f"[BlurBGMessage] Error in unload: {e}")
