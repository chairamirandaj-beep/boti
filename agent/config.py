import os

# --- Supabase (igual que el proyecto boti) ---
SUPABASE_URL = "https://zhezpmyrqpzmlmbzxdjx.supabase.co"
SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpoZXpwbXlycXB6bWxtYnp4ZGp4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEwMjA5MTEsImV4cCI6MjA5NjU5NjkxMX0.IRmeITfzCUxrxud-ekLFj1Ij7QNEmsL6n4CXx3Kx4uc"

# --- OpenAI ---
# Pon tu API key aquí o en variable de entorno OPENAI_API_KEY
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")

# --- Teléfono ---
SCREEN_W = 1080
SCREEN_H = 2340
ADB_DEVICE = None  # None = primer dispositivo conectado
