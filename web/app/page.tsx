'use client'

import { useEffect, useState } from 'react'
import { supabase, Device, Log } from '@/lib/supabase'

const LOG_COLOR: Record<string, string> = {
  info:  'text-gray-300',
  warn:  'text-yellow-400',
  error: 'text-red-400',
}

// Acciones de LIVE que usan coordenada (calibrables por teléfono). def = valor por defecto.
const CALIB: { key: string; label: string; def: [number, number] }[] = [
  { key: 'gift_icon',    label: 'Ícono de regalo 🎁',           def: [900, 2246] },
  { key: 'live_chat',    label: 'Barra de chat del live',       def: [200, 2235] },
  { key: 'live_send',    label: 'Enviar chat (sin teclado)',    def: [598, 2235] },
  { key: 'live_send_kb', label: 'Enviar chat (con teclado)',    def: [1000, 1505] },
]

const FRESH_MS = 40_000
function deviceState(d: Device): 'paused' | 'online' | 'offline' {
  if (d.status === 'paused') return 'paused'
  if (d.last_seen && Date.now() - new Date(d.last_seen).getTime() < FRESH_MS) return 'online'
  return 'offline'
}
const STATE_DOT: Record<string, string>   = { online: 'bg-green-500', offline: 'bg-gray-600', paused: 'bg-orange-400' }
const STATE_LABEL: Record<string, string> = { online: 'ACTIVO', offline: 'DESCONECTADO', paused: 'PAUSADO' }
const STATE_TEXT: Record<string, string>  = { online: 'text-green-400', offline: 'text-gray-500', paused: 'text-orange-400' }

function Btn({ onClick, disabled, color, span, children }: {
  onClick: () => void
  disabled: boolean
  color: string
  span?: boolean
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`${color} disabled:opacity-40 px-4 py-3 rounded-xl text-sm font-semibold transition active:scale-95 ${span ? 'col-span-2' : ''}`}
    >
      {children}
    </button>
  )
}

export default function Home() {
  const [devices, setDevices]   = useState<Device[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)  // id de teléfono, 'ALL' o null (lista)
  const [logs, setLogs]         = useState<Log[]>([])
  const [sending, setSending]   = useState(false)
  const [liveLink, setLiveLink] = useState('')
  const [fromLive, setFromLive] = useState(false)
  const [, setNow]              = useState(Date.now())

  useEffect(() => {
    fetchDevices()
    fetchLogs()
    const channel = supabase
      .channel('realtime-panel')
      .on('postgres_changes', { event: '*',      schema: 'public', table: 'devices' }, () => fetchDevices())
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'logs'    }, (p) => {
        setLogs(prev => [p.new as Log, ...prev].slice(0, 80))
      })
      .subscribe()
    const tick = setInterval(() => setNow(Date.now()), 10_000)
    return () => { supabase.removeChannel(channel); clearInterval(tick) }
  }, [])

  const fetchDevices = async () => {
    const { data } = await supabase.from('devices').select('*').order('name', { ascending: true })
    if (data) setDevices(data as Device[])
  }
  const fetchLogs = async () => {
    const { data } = await supabase.from('logs').select('*')
      .order('created_at', { ascending: false }).limit(80)
    if (data) setLogs(data)
  }

  // Destinos según el panel abierto
  const targets: string[] =
    activeId === 'ALL' ? devices.filter(d => deviceState(d) === 'online').map(d => d.id)
    : activeId ? [activeId] : []

  const active = activeId && activeId !== 'ALL' ? devices.find(d => d.id === activeId) ?? null : null

  const sendCommand = async (action: string, payload?: string) => {
    if (targets.length === 0) return
    setSending(true)
    await supabase.from('commands').insert(
      targets.map(id => ({ device_id: id, action, payload: payload ?? null, status: 'pending' }))
    )
    setSending(false)
  }

  const renameDevice = async (d: Device) => {
    const name = prompt('Nombre del teléfono:', d.name)?.trim()
    if (name && name !== d.name) { await supabase.from('devices').update({ name }).eq('id', d.id); fetchDevices() }
  }
  const deleteDevice = async (d: Device) => {
    if (!confirm(`¿Eliminar "${d.name}"? Se borrarán también sus logs y comandos.`)) return
    await supabase.from('logs').delete().eq('device_id', d.id)
    await supabase.from('commands').delete().eq('device_id', d.id)
    await supabase.from('devices').delete().eq('id', d.id)
    if (activeId === d.id) setActiveId(null)
    fetchDevices(); fetchLogs()
  }
  const saveCoord = async (d: Device, key: string, val: [number, number]) => {
    await supabase.from('devices').update({ coords: { ...(d.coords ?? {}), [key]: val } }).eq('id', d.id)
    fetchDevices()
  }
  const resetCoord = async (d: Device, key: string) => {
    const coords = { ...(d.coords ?? {}) }; delete coords[key]
    await supabase.from('devices').update({ coords }).eq('id', d.id); fetchDevices()
  }

  const ask = (label: string) => { const v = prompt(label); return v?.trim() || null }
  const off = sending || targets.length === 0
  const deviceName = (id: string | null) => devices.find(d => d.id === id)?.name ?? '—'

  // ───────────────────────── LISTA DE TELÉFONOS ─────────────────────────
  if (!activeId) {
    const onlineCount = devices.filter(d => deviceState(d) === 'online').length
    return (
      <main className="min-h-screen bg-gray-950 text-white p-4 font-mono max-w-lg mx-auto">
        <h1 className="text-lg font-bold tracking-widest mb-4 text-gray-100">BOTI CONTROL</h1>

        <div className="flex items-center justify-between mb-2">
          <p className="text-xs text-gray-500 tracking-widest">TELÉFONOS ({devices.length})</p>
          <p className="text-xs text-gray-600">{onlineCount} activos</p>
        </div>

        {devices.length > 1 && (
          <button onClick={() => setActiveId('ALL')}
            disabled={onlineCount === 0}
            className="w-full mb-3 px-4 py-3 rounded-xl text-sm font-semibold bg-purple-800 hover:bg-purple-700 disabled:opacity-40 transition active:scale-95">
            📢 Panel grupal — enviar a {onlineCount} activos
          </button>
        )}

        <div className="space-y-2 mb-4">
          {devices.length === 0 ? (
            <p className="text-gray-600 text-xs p-2">Sin teléfonos. Instala la app en un teléfono y aparecerá aquí.</p>
          ) : devices.map(d => {
            const st = deviceState(d)
            return (
              <div key={d.id}
                onClick={() => setActiveId(d.id)}
                className="flex items-center gap-3 px-3 py-3 rounded-xl cursor-pointer transition bg-gray-900 border border-gray-800 hover:border-gray-600">
                <div className={`w-2.5 h-2.5 rounded-full ${STATE_DOT[st]} ${st === 'online' ? 'animate-pulse' : ''}`} />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold truncate">{d.name}</p>
                  <p className="text-[10px] text-gray-600 truncate">
                    {d.current_account ? `👤 ${d.current_account}` : 'cuenta ?'}
                    {' · '}{d.screen_w && d.screen_h ? `${d.screen_w}×${d.screen_h}` : 'res ?'}
                  </p>
                </div>
                <span className={`text-[10px] font-bold ${STATE_TEXT[st]}`}>{STATE_LABEL[st]}</span>
                <button onClick={(e) => { e.stopPropagation(); renameDevice(d) }}
                  className="text-gray-600 hover:text-gray-300 text-sm px-1">✎</button>
                <button onClick={(e) => { e.stopPropagation(); deleteDevice(d) }}
                  className="text-gray-600 hover:text-red-400 text-sm px-1">🗑</button>
                <span className="text-gray-600">›</span>
              </div>
            )
          })}
        </div>

        <LogsBox logs={logs} deviceName={deviceName} />
      </main>
    )
  }

  // ───────────────────────── PANEL DE UN TELÉFONO (o grupal) ─────────────────────────
  const headerTitle = activeId === 'ALL' ? `📢 Grupal (${targets.length} activos)` : active?.name ?? '—'
  const st = active ? deviceState(active) : 'online'

  return (
    <main className="min-h-screen bg-gray-950 text-white p-4 font-mono max-w-lg mx-auto">
      <button onClick={() => setActiveId(null)} className="text-xs text-gray-400 hover:text-gray-200 mb-3">← Teléfonos</button>

      {/* Cabecera del teléfono */}
      <div className="bg-gray-900 rounded-xl p-3 mb-4 border border-gray-800 flex items-center gap-3">
        {active && <div className={`w-2.5 h-2.5 rounded-full ${STATE_DOT[st]} ${st === 'online' ? 'animate-pulse' : ''}`} />}
        <div className="flex-1 min-w-0">
          <p className="font-semibold text-sm truncate">{headerTitle}</p>
          {active && (
            <p className="text-[10px] text-gray-600 truncate">
              {active.current_account ? `👤 ${active.current_account} · ` : ''}
              {active.screen_w && active.screen_h ? `${active.screen_w}×${active.screen_h}` : 'resolución ?'}
            </p>
          )}
        </div>
        {active && <span className={`text-[10px] font-bold ${STATE_TEXT[st]}`}>{STATE_LABEL[st]}</span>}
      </div>

      {/* ── TikTok ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">TIKTOK</p>
      <div className="grid grid-cols-2 gap-2 mb-4">
        <Btn onClick={() => sendCommand('TIKTOK_OPEN')} disabled={off} color="bg-gray-700 hover:bg-gray-600">Abrir TikTok</Btn>
        <Btn onClick={() => sendCommand('TIKTOK_LIKE')} disabled={off} color="bg-pink-700 hover:bg-pink-600">Like ♥</Btn>
        <Btn onClick={() => { const t = ask('Texto del comentario:'); if (t) sendCommand('TIKTOK_COMMENT', t) }}
          disabled={off} color="bg-cyan-700 hover:bg-cyan-600">Comentar 💬</Btn>
        <Btn onClick={() => sendCommand('TIKTOK_SAVE')} disabled={off} color="bg-indigo-700 hover:bg-indigo-600">Guardar 🔖</Btn>
        <Btn onClick={() => sendCommand('SCROLL')} disabled={off} color="bg-blue-700 hover:bg-blue-600" span>Scroll ↑</Btn>
      </div>

      {/* ── Cuentas (solo teléfono individual) ── */}
      {active && (
        <>
          <div className="flex items-center justify-between mb-2">
            <p className="text-xs text-gray-500 tracking-widest">CUENTAS</p>
            <button onClick={() => setFromLive(v => !v)}
              className={`text-xs px-2 py-1 rounded-lg border transition ${
                fromLive ? 'bg-rose-700 border-rose-600 text-white' : 'bg-gray-900 border-gray-700 text-gray-400'}`}>
              {fromLive ? '🔴 Desde live' : '○ Desde live'}
            </button>
          </div>
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-3 mb-4">
            {(active.tiktok_accounts ?? []).length > 0 ? (
              <div className="grid grid-cols-2 gap-2 mb-2">
                {(active.tiktok_accounts as string[]).map((acc) => {
                  const isCurrent = active.current_account === acc
                  return (
                    <Btn key={acc} onClick={() => sendCommand(fromLive ? 'TIKTOK_LIVE_SWITCH_ACCOUNT' : 'TIKTOK_SWITCH_ACCOUNT', acc)}
                      disabled={off} color={isCurrent ? 'bg-green-700 hover:bg-green-600 ring-2 ring-green-400' : 'bg-yellow-700 hover:bg-yellow-600'}>
                      {isCurrent ? '✓ ' : '👤 '}{acc}
                    </Btn>
                  )
                })}
              </div>
            ) : (
              <p className="text-gray-600 text-xs mb-2">Sin cuentas — toca Sincronizar</p>
            )}
            <button onClick={() => sendCommand('TIKTOK_GET_ACCOUNTS')} disabled={off}
              className="w-full text-xs text-gray-400 hover:text-gray-200 disabled:opacity-40 py-1.5 border border-gray-700 rounded-lg transition">
              ↺ Sincronizar cuentas del teléfono
            </button>
          </div>
        </>
      )}

      {/* ── TikTok Live ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">TIKTOK LIVE</p>
      <div className="flex gap-2 mb-2">
        <input type="text" value={liveLink} onChange={(e) => setLiveLink(e.target.value)}
          placeholder="Link del live (https://www.tiktok.com/@user/live)"
          className="flex-1 bg-gray-900 border border-gray-800 rounded-xl px-3 py-2 text-xs text-gray-200 placeholder-gray-600 focus:outline-none focus:border-gray-600" />
        <Btn onClick={() => { if (liveLink.trim()) sendCommand('TIKTOK_OPEN_LIVE', liveLink.trim()) }}
          disabled={off || !liveLink.trim()} color="bg-emerald-700 hover:bg-emerald-600">Abrir Live 📺</Btn>
      </div>
      <div className="grid grid-cols-2 gap-2 mb-4">
        <Btn onClick={() => { const t = ask('Comentario para el live:'); if (t) sendCommand('TIKTOK_LIVE_COMMENT', t) }}
          disabled={off} color="bg-rose-700 hover:bg-rose-600">Chat Live 🔴</Btn>
        <Btn onClick={() => { const g = ask('Nombre del regalo (ej: Rosa) — vacío = ver lista:'); sendCommand('TIKTOK_LIVE_GIFT', g ?? undefined) }}
          disabled={off} color="bg-fuchsia-700 hover:bg-fuchsia-600">Regalo 🎁</Btn>
      </div>

      {/* ── Automatizaciones (solo teléfono individual) ── */}
      {active && (
        <>
          <p className="text-xs text-gray-500 mb-2 tracking-widest">AUTOMATIZACIONES</p>
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-3 mb-4">
            <button
              onClick={() => {
                if (!liveLink.trim()) { alert('Primero pega el link del live arriba (sección TIKTOK LIVE).'); return }
                const accts = active.tiktok_accounts ?? []
                const account = prompt(`Cuenta a usar:${accts.length ? `\nDisponibles: ${accts.join(', ')}` : ''}`, accts[0] ?? '')?.trim()
                if (!account) return
                const comment = prompt('Comentario para el live:', 'hola')?.trim() || 'hola'
                sendCommand('TIKTOK_AUTO_LIVE', JSON.stringify({ account, link: liveLink.trim(), comment }))
              }}
              disabled={off}
              className="w-full px-4 py-3 rounded-xl text-sm font-semibold bg-violet-700 hover:bg-violet-600 disabled:opacity-40 transition active:scale-95">
              🤖 Cambiar cuenta → Abrir live → Comentar
            </button>
            <p className="text-[11px] text-gray-600 mt-2">
              Usa el link de la sección TIKTOK LIVE. Te pedirá la cuenta y el comentario (por defecto &quot;hola&quot;).
            </p>
          </div>
        </>
      )}

      {/* ── Control ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">CONTROL</p>
      <div className="grid grid-cols-3 gap-2 mb-4">
        <Btn onClick={() => sendCommand('DEBUG_NODES')} disabled={off} color="bg-gray-700 hover:bg-gray-600">Nodos 🔍</Btn>
        <Btn onClick={() => sendCommand('DEBUG_ALL')} disabled={off} color="bg-gray-700 hover:bg-gray-600">Clicks 🔎</Btn>
        <Btn onClick={() => sendCommand('STOP')} disabled={off} color="bg-red-800 hover:bg-red-700">STOP</Btn>
      </div>

      {/* ── Calibración LIVE (solo teléfono individual) ── */}
      {active && (
        <>
          <p className="text-xs text-gray-500 mb-2 tracking-widest">CALIBRACIÓN LIVE</p>
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-3 mb-4 space-y-1.5">
            <p className="text-[11px] text-gray-600 mb-1">
              Activa &quot;ubicación del puntero&quot; en el teléfono, toca el elemento del live para ver su x,y y fíjalo aquí.
            </p>
            <button onClick={() => sendCommand('DEBUG_COORDS')} disabled={off}
              className="w-full text-xs text-gray-400 hover:text-gray-200 disabled:opacity-40 py-1 mb-1 border border-gray-700 rounded-lg transition">
              🔎 Ver coords cargadas en el teléfono (logs)
            </button>
            {CALIB.map(c => {
              const cur = active.coords?.[c.key]
              const [x, y] = cur ?? c.def
              return (
                <div key={c.key} className="flex items-center gap-2 text-xs">
                  <span className="flex-1 text-gray-300 truncate">{c.label}</span>
                  <span className={`font-mono ${cur ? 'text-green-400' : 'text-gray-500'}`}>{x},{y}{cur ? '' : ' (def)'}</span>
                  <button onClick={() => {
                      const v = prompt(`${c.label}\nCoordenada x,y:`, `${x},${y}`)
                      const m = v?.split(',').map(s => parseInt(s.trim(), 10))
                      if (m && m.length === 2 && m.every(n => !isNaN(n))) saveCoord(active, c.key, [m[0], m[1]])
                    }}
                    className="px-2 py-1 rounded-lg bg-gray-700 hover:bg-gray-600 text-gray-200">Fijar</button>
                  {cur && <button onClick={() => resetCoord(active, c.key)} className="px-1 text-gray-500 hover:text-gray-300" title="Volver al default">↺</button>}
                </div>
              )
            })}
          </div>
        </>
      )}

      <LogsBox logs={logs} deviceName={deviceName} />
    </main>
  )
}

function LogsBox({ logs, deviceName }: { logs: Log[]; deviceName: (id: string | null) => string }) {
  return (
    <div className="bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 border-b border-gray-800">
        <p className="text-xs text-gray-500 tracking-widest">PASOS EN VIVO</p>
        <p className="text-xs text-gray-600">{logs.length} entradas</p>
      </div>
      <div className="h-72 overflow-y-auto p-3 space-y-1">
        {logs.length === 0 ? (
          <p className="text-gray-600 text-xs">Aquí verás cada paso que hacen los teléfonos...</p>
        ) : (
          logs.map(log => (
            <div key={log.id} className={`text-xs ${LOG_COLOR[log.level] ?? 'text-gray-300'}`}>
              <span className="text-gray-600 mr-2">{new Date(log.created_at).toLocaleTimeString()}</span>
              <span className="text-blue-400 mr-1">[{deviceName(log.device_id)}]</span>
              {log.message}
            </div>
          ))
        )}
      </div>
    </div>
  )
}
