import subprocess
import io
from PIL import Image

def _run(*args):
    return subprocess.run(["adb", *args], capture_output=True)

def screenshot() -> Image.Image:
    result = _run("exec-out", "screencap", "-p")
    return Image.open(io.BytesIO(result.stdout))

def tap(x: int, y: int):
    _run("shell", "input", "tap", str(x), str(y))

def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 350):
    _run("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))

def scroll_up():
    """Siguiente video en TikTok"""
    swipe(540, 1900, 540, 500, 350)

def scroll_down():
    """Video anterior en TikTok"""
    swipe(540, 500, 540, 1900, 350)

def key_back():
    _run("shell", "input", "keyevent", "4")

def key_home():
    _run("shell", "input", "keyevent", "3")

def open_app(package: str):
    _run("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")

def is_connected() -> bool:
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = result.stdout.strip().split("\n")
    return any("device" in line and "List" not in line for line in lines)
