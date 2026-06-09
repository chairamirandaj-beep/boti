import time
import sys
from supabase import create_client
import adb
import vision
import tasks as task_defs
from config import SUPABASE_URL, SUPABASE_KEY

db = create_client(SUPABASE_URL, SUPABASE_KEY)

# ── Supabase helpers ──────────────────────────────────────────────────────────

def get_device_id() -> str | None:
    r = db.table("devices").select("id").order("last_seen", desc=True).limit(1).execute()
    return r.data[0]["id"] if r.data else None

def log(device_id: str, level: str, msg: str):
    try:
        db.table("logs").insert({"device_id": device_id, "level": level, "message": msg}).execute()
        print(f"[{level.upper()}] {msg}")
    except Exception as e:
        print(f"[LOG ERROR] {e}")

def mark_command(cmd_id: str, status: str):
    db.table("commands").update({"status": status}).eq("id", cmd_id).execute()

def cancel_pending(device_id: str):
    db.table("commands").update({"status": "cancelled"}).eq("device_id", device_id).eq("status", "pending").execute()

# ── Ejecutor de tareas con IA ─────────────────────────────────────────────────

def run_task(device_id: str, action: str, payload: str | None):
    action_up = action.upper()

    # Acciones directas sin IA
    if action_up == "SCROLL":
        adb.scroll_up()
        log(device_id, "info", "Scroll ejecutado")
        return

    if action_up == "TIKTOK_OPEN":
        adb.open_app("com.zhiliaoapp.musically")
        time.sleep(4)
        log(device_id, "info", "TikTok abierto")
        return

    if action_up == "SCREENSHOT":
        img = adb.screenshot()
        img.save("last_screenshot.png")
        log(device_id, "info", "Screenshot guardado como last_screenshot.png")
        return

    task = task_defs.get_task(action_up, payload)
    if task is None:
        log(device_id, "error", f"Acción no reconocida: {action}")
        return

    log(device_id, "info", f"Iniciando {action_up} con IA...")

    for step in range(12):  # máximo 12 pasos por tarea
        img = adb.screenshot()

        try:
            result = vision.decide(img, task)
        except Exception as e:
            log(device_id, "error", f"Error GPT-4o: {e}")
            return

        act    = result.get("action", "error")
        reason = result.get("reason", "")
        done   = result.get("done", False)

        log(device_id, "info", f"  Paso {step+1}: {act} — {reason}")

        if act == "done" or done:
            log(device_id, "info", f"Completado: {action_up}")
            return

        if act == "error":
            log(device_id, "error", f"IA no puede completar: {reason}")
            return

        # Ejecutar acción
        if act == "tap":
            x, y = int(result.get("x", 540)), int(result.get("y", 1170))
            adb.tap(x, y)
        elif act == "swipe_up":
            adb.scroll_up()
        elif act == "swipe_down":
            adb.scroll_down()
        elif act == "key_back":
            adb.key_back()
        elif act == "key_home":
            adb.key_home()

        time.sleep(1.5)  # espera que la pantalla responda

    log(device_id, "warn", f"Máximo de pasos alcanzado para {action_up}")

# ── Loop principal ────────────────────────────────────────────────────────────

def main():
    if not adb.is_connected():
        print("ERROR: No hay teléfono conectado por USB.")
        print("Conecta el teléfono y activa Depuración USB.")
        sys.exit(1)

    device_id = get_device_id()
    if not device_id:
        print("ERROR: No hay dispositivo registrado en Supabase.")
        print("Abre la app en el teléfono primero.")
        sys.exit(1)

    print(f"Agente iniciado — device: {device_id[:8]}...")
    db.table("devices").update({"status": "online", "connected": True}).eq("id", device_id).execute()
    log(device_id, "info", "Agente PC activo (ADB + GPT-4o Vision)")

    stop_flag = False

    while True:
        try:
            r = (db.table("commands")
                   .select("*")
                   .eq("device_id", device_id)
                   .eq("status", "pending")
                   .order("created_at")
                   .limit(1)
                   .execute())

            if r.data:
                cmd     = r.data[0]
                cmd_id  = cmd["id"]
                action  = cmd["action"]
                payload = cmd.get("payload")

                if action.upper() == "STOP":
                    stop_flag = True
                    cancel_pending(device_id)
                    mark_command(cmd_id, "done")
                    log(device_id, "info", "STOP: cola limpiada")
                else:
                    stop_flag = False
                    mark_command(cmd_id, "executing")
                    run_task(device_id, action, payload)
                    mark_command(cmd_id, "done")

        except KeyboardInterrupt:
            print("\nAgente detenido.")
            db.table("devices").update({"status": "offline"}).eq("id", device_id).execute()
            break
        except Exception as e:
            print(f"[ERROR LOOP] {e}")

        time.sleep(2)

if __name__ == "__main__":
    main()
