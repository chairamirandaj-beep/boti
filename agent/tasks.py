# Descripción de cada tarea para GPT-4o
# {payload} se reemplaza con el valor del comando

TASKS = {
    "TIKTOK_LIKE": (
        "Estás en TikTok. Quiero dar like al video actual. "
        "Buscá el botón de corazón en el panel derecho de la pantalla. "
        "Si el corazón está vacío/blanco, tocalo para dar like. "
        "Si ya está rojo/rosado/lleno, el video ya tiene like — respondé done. "
        "Devolvé las coordenadas exactas del botón de corazón."
    ),
    "TIKTOK_SCROLL": (
        "Estás en TikTok. Hacé scroll hacia arriba para pasar al siguiente video."
    ),
    "TIKTOK_FOLLOW": (
        "Estás en TikTok. Quiero seguir al creador del video actual. "
        "Buscá el botón Follow/Seguir (generalmente está debajo de la foto de perfil "
        "en el lado derecho, o en la descripción). "
        "Si ya dice Following/Siguiendo, ya está seguido — respondé done. "
        "Tocá el botón para seguirlo."
    ),
    "TIKTOK_SWITCH_ACCOUNT": (
        "Estás en TikTok. Quiero cambiar a la cuenta '{payload}'. "
        "Navegá a: pestaña Perfil (abajo a la derecha) → tocá el nombre de usuario "
        "o el ícono de cambio de cuenta → seleccioná la cuenta '{payload}' de la lista. "
        "Las cuentas ya están guardadas, no hay que ingresar datos."
    ),
    "TIKTOK_COMMENT": (
        "Estás en TikTok. Quiero comentar en el video actual con: '{payload}'. "
        "Tocá el botón de comentarios (ícono de burbuja de chat en el panel derecho), "
        "luego tocá el campo de texto, escribí '{payload}' y enviá."
    ),
    "TIKTOK_OPEN": (
        "Abrí la app de TikTok. Si ya está abierta, respondé done."
    ),
    "WHATSAPP_TAB": (
        "Estás en WhatsApp. Navegá a la pestaña '{payload}'. "
        "Buscá la pestaña con ese nombre en la parte superior y tocala."
    ),
    "SCREENSHOT": None,  # no necesita IA, solo toma la foto
    "SCROLL": None,       # no necesita IA, scroll directo
    "STOP": None,         # manejado por el loop principal
}

def get_task(action: str, payload: str | None) -> str | None:
    template = TASKS.get(action.upper())
    if template is None:
        return None
    return template.format(payload=payload or "")
