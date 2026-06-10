import time
import sys
import os
import io
import base64
import json
import subprocess
from PIL import Image
from openai import OpenAI
from supabase import create_client
from dotenv import load_dotenv

load_dotenv()

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
SUPABASE_URL   = "https://zhezpmyrqpzmlmbzxdjx.supabase.co"
SUPABASE_KEY   = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpoZXpwbXlycXB6bWxtYnp4ZGp4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEwMjA5MTEsImV4cCI6MjA5NjU5NjkxMX0.IRmeITfzCUxrxud-ekLFj1Ij7QNEmsL6n4CXx3Kx4uc"

VERSION = "v5-heart-tap"
print(f"[BOTI AGENT {VERSION}] {os.path.abspath(__file__)}")

ai = OpenAI(api_key=OPENAI_API_KEY)
db = create_client(SUPABASE_URL, SUPABASE_KEY)

# ── ADB ───────────────────────────────────────────────────────────────────────

def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True)

def screenshot() -> Image.Image:
    r = adb("exec-out", "screencap", "-p")
    img = Image.open(io.BytesIO(r.stdout))
    return img.convert("RGB")  # siempre RGB, sin excepción

def tap(x: int, y: int):
    adb("shell", "input", "tap", str(x), str(y))

def double_tap(x: int, y: int):
    """Doble tap — gesto nativo de TikTok para dar like"""
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.1)
    adb("shell", "input", "tap", str(x), str(y))

def scroll_up():
    adb("shell", "input", "swipe", "540", "1900", "540", "500", "350")

def open_app(pkg: str):
    adb("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")

def key_back():
    adb("shell", "input", "keyevent", "4")

# ── GPT-4o Vision ─────────────────────────────────────────────────────────────

SYSTEM = """Controlás un Samsung Galaxy A55 (pantalla 1080x2340 píxeles).
Recibís una captura de pantalla y una tarea. Respondé SOLO con JSON válido.

Formato:
{
  "action": "tap" | "swipe_up" | "swipe_down" | "key_back" | "done" | "error",
  "x": <entero 0-1080, solo si action=tap>,
  "y": <entero 0-2340, solo si action=tap>,
  "reason": "<explicación breve>",
  "done": <true si tarea completa>
}"""

def ask_ai(img: Image.Image, task: str) -> dict:
    buf = io.BytesIO()
    img.save(buf, format="PNG")           # PNG soporta cualquier modo
    b64 = base64.b64encode(buf.getvalue()).decode()

    resp = ai.chat.completions.create(
        model="gpt-4o",
        response_format={"type": "json_object"},
        max_tokens=300,
        messages=[
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
                {"type": "text", "text": task},
            ]},
        ],
    )
    return json.loads(resp.choices[0].message.content)

# ── Tareas ────────────────────────────────────────────────────────────────────

TASKS = {
    "TIKTOK_LIKE": (
        "Estás en TikTok. Quiero dar like al video actual. "
        "Buscá el ícono de corazón en el panel derecho. "
        "Si está vacío/blanco tocalo. Si ya es rojo/rosado respondé done."
    ),
    "TIKTOK_FOLLOW": (
        "Estás en TikTok. Quiero seguir al creador del video actual. "
        "Buscá el botón Follow/Seguir. Si ya dice Following respondé done."
    ),
    "TIKTOK_SWITCH_ACCOUNT": (
        "Estás en TikTok. Cambiá a la cuenta '{p}'. "
        "Andá a Perfil → tocá el nombre de usuario arriba → seleccioná '{p}' de la lista."
    ),
    "TIKTOK_COMMENT": (
        "Estás en TikTok. Comentá en el video: '{p}'. "
        "Tocá el ícono de comentarios → tocá el campo de texto → escribí '{p}' → enviá."
    ),
    "WHATSAPP_TAB": (
        "Estás en WhatsApp. Andá a la pestaña '{p}'. Buscala en la parte superior y tocala."
    ),
}

def get_task(action: str, payload: str) -> str | None:
    t = TASKS.get(action.upper())
    return t.replace("{p}", payload or "") if t else None

# ── Supabase helpers ──────────────────────────────────────────────────────────

def log(device_id: str, level: str, msg: str):
    try:
        db.table("logs").insert({"device_id": device_id, "level": level, "message": msg}).execute()
    except Exception:
        pass
    print(f"  [{level.upper()}] {msg}")

def mark(cmd_id: str, status: str):
    db.table("commands").update({"status": status}).eq("id", cmd_id).execute()

def cancel_pending(device_id: str):
    db.table("commands").update({"status": "cancelled"}).eq("device_id", device_id).eq("status", "pending").execute()

# ── Ejecutor ──────────────────────────────────────────────────────────────────

def run(device_id: str, action: str, payload: str | None):
    up = action.upper()
    p  = payload or ""

    if up == "SCROLL":
        scroll_up()
        log(device_id, "info", "Scroll ejecutado")
        return

    if up == "TIKTOK_OPEN":
        open_app("com.zhiliaoapp.musically")
        time.sleep(4)
        log(device_id, "info", "TikTok abierto")
        return

    if up == "TIKTOK_LIKE":
        # Tap directo al botón corazón (panel derecho, ~x=1010 y=860)
        # Más confiable que doble tap al centro: sin problemas de timing
        tap(1010, 860)
        time.sleep(0.5)
        log(device_id, "info", "Like enviado (tap botón corazón)")
        return

    if up == "SCREENSHOT":
        screenshot().save("last_screenshot.png")
        log(device_id, "info", "Screenshot guardado")
        return

    task = get_task(up, p)
    if not task:
        log(device_id, "error", f"Acción desconocida: {action}")
        return

    log(device_id, "info", f"Iniciando {up}...")

    for step in range(12):
        img = screenshot()

        try:
            r = ask_ai(img, task)
        except Exception as e:
            log(device_id, "error", f"Error IA: {e}")
            return

        act    = r.get("action", "error")
        reason = r.get("reason", "")
        done   = r.get("done", False)

        log(device_id, "info", f"Paso {step+1}: {act} — {reason}")

        if act == "done" or done:
            log(device_id, "info", f"Completado: {up}")
            return
        if act == "error":
            log(device_id, "error", f"IA: {reason}")
            return
        if act == "tap":
            tap(int(r.get("x", 540)), int(r.get("y", 1170)))
        elif act == "swipe_up":
            scroll_up()
        elif act == "key_back":
            key_back()

        time.sleep(1.5)

    log(device_id, "warn", f"Pasos máximos para {up}")

# ── Main loop ─────────────────────────────────────────────────────────────────

def main():
    r = db.table("devices").select("id").order("last_seen", desc=True).limit(1).execute()
    if not r.data:
        print("ERROR: sin dispositivo en Supabase. Abrí la app primero.")
        sys.exit(1)

    device_id = r.data[0]["id"]
    print(f"Device: {device_id[:8]}...")

    db.table("devices").update({"status": "online", "connected": True}).eq("id", device_id).execute()
    log(device_id, "info", f"Agente {VERSION} activo")

    while True:
        try:
            cmds = (db.table("commands")
                      .select("*")
                      .eq("device_id", device_id)
                      .eq("status", "pending")
                      .order("created_at")
                      .limit(1)
                      .execute())

            if cmds.data:
                cmd    = cmds.data[0]
                cid    = cmd["id"]
                action = cmd["action"]
                pl     = cmd.get("payload")

                if action.upper() == "STOP":
                    cancel_pending(device_id)
                    mark(cid, "done")
                    log(device_id, "info", "STOP: cola limpiada")
                else:
                    mark(cid, "executing")
                    run(device_id, action, pl)
                    mark(cid, "done")

        except KeyboardInterrupt:
            print("\nDetenido.")
            db.table("devices").update({"status": "offline"}).eq("id", device_id).execute()
            break
        except Exception as e:
            print(f"[ERROR] {e}")

        time.sleep(2)

if __name__ == "__main__":
    main()
