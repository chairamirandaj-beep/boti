import base64
import json
import io
from PIL import Image
from openai import OpenAI
from config import OPENAI_API_KEY

client = OpenAI(api_key=OPENAI_API_KEY)

SYSTEM = """Controlás un Samsung Galaxy A55 (pantalla 1080x2340 píxeles).
Recibís una captura de pantalla y una tarea. Devolvés SOLO JSON válido con la siguiente acción.

Formato de respuesta:
{
  "action": "tap" | "swipe_up" | "swipe_down" | "key_back" | "key_home" | "done" | "error",
  "x": <entero, solo para tap>,
  "y": <entero, solo para tap>,
  "reason": "<explicación breve>",
  "done": <true si la tarea está completamente terminada>
}

Reglas:
- Coordenadas: x entre 0-1080, y entre 0-2340
- swipe_up: deslizar hacia arriba (siguiente video TikTok)
- swipe_down: deslizar hacia abajo (video anterior)
- key_back: botón atrás de Android
- key_home: botón home de Android
- done: tarea terminada, no hay más acciones que hacer
- error: no se puede completar (explicar en reason)
- Respondé SOLO el JSON, sin texto adicional"""

def decide(img: Image.Image, task: str) -> dict:
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)
    b64 = base64.b64encode(buf.getvalue()).decode()

    response = client.chat.completions.create(
        model="gpt-4o",
        messages=[
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                {"type": "text", "text": task}
            ]}
        ],
        max_tokens=300,
        response_format={"type": "json_object"}
    )

    return json.loads(response.choices[0].message.content)
