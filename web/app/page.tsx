'use client'

import { useEffect, useState } from 'react'
import { supabase, Device, Log } from '@/lib/supabase'

const LOG_COLOR: Record<string, string> = {
  info:  'text-gray-300',
  warn:  'text-yellow-400',
  error: 'text-red-400',
}

// Un teléfono se considera ACTIVO si reportó latido en los últimos 40s.
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
  const [selected, setSelected] = useState<string[]>([])
  const [logs, setLogs]         = useState<Log[]>([])
  const [sending, setSending]   = useState(false)
  const [liveLink, setLiveLink] = useState('')
  const [fromLive, setFromLive] = useState(false)
  const [now, setNow]           = useState(Date.now())

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

    // Recalcular "activo/desconectado" cada 10s (los latidos llegan cada ~10s)
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

  const sendCommand = async (action: string, payload?: string) => {
    if (selected.length === 0) return
    setSending(true)
    const rows = selected.map(id => ({
      device_id: id, action, payload: payload ?? null, status: 'pending',
    }))
    await supabase.from('commands').insert(rows)
    setSending(false)
  }

  const renameDevice = async (d: Device) => {
    const name = prompt('Nombre del teléfono:', d.name)?.trim()
    if (name && name !== d.name) {
      await supabase.from('devices').update({ name }).eq('id', d.id)
      fetchDevices()
    }
  }

  const toggle = (id: string) =>
    setSelected(s => s.includes(id) ? s.filter(x => x !== id) : [...s, id])
  const selectAll    = () => setSelected(devices.map(d => d.id))
  const selectActive = () => setSelected(devices.filter(d => deviceState(d) === 'online').map(d => d.id))
  const selectNone   = () => setSelected([])

  const ask = (label: string) => { const v = prompt(label); return v?.trim() || null }

  void now // forzar recálculo de estados con el tick
  const off = sending || selected.length === 0
  const singleSel = selected.length === 1 ? devices.find(d => d.id === selected[0]) ?? null : null
  const deviceName = (id: string | null) => devices.find(d => d.id === id)?.name ?? '—'

  return (
    <main className="min-h-screen bg-gray-950 text-white p-4 font-mono max-w-lg mx-auto">

      <h1 className="text-lg font-bold tracking-widest mb-4 text-gray-100">BOTI CONTROL</h1>

      {/* ── Teléfonos ── */}
      <div className="flex items-center justify-between mb-2">
        <p className="text-xs text-gray-500 tracking-widest">TELÉFONOS ({devices.length})</p>
        <div className="flex gap-1">
          <button onClick={selectActive} className="text-xs px-2 py-1 rounded-lg border border-gray-700 text-gray-400 hover:text-gray-200">Activos</button>
          <button onClick={selectAll}    className="text-xs px-2 py-1 rounded-lg border border-gray-700 text-gray-400 hover:text-gray-200">Todos</button>
          <button onClick={selectNone}   className="text-xs px-2 py-1 rounded-lg border border-gray-700 text-gray-400 hover:text-gray-200">Ninguno</button>
        </div>
      </div>
      <div className="bg-gray-900 rounded-xl border border-gray-800 p-2 mb-3 space-y-1">
        {devices.length === 0 ? (
          <p className="text-gray-600 text-xs p-2">Sin teléfonos. Instala la app en un teléfono y aparecerá aquí.</p>
        ) : devices.map(d => {
          const st = deviceState(d)
          const sel = selected.includes(d.id)
          return (
            <div key={d.id}
              onClick={() => toggle(d.id)}
              className={`flex items-center gap-3 px-2 py-2 rounded-lg cursor-pointer transition border ${
                sel ? 'bg-gray-800 border-gray-600' : 'border-transparent hover:bg-gray-850'}`}>
              <div className={`w-4 h-4 rounded border flex items-center justify-center text-[10px] ${
                sel ? 'bg-blue-600 border-blue-500' : 'border-gray-600'}`}>
                {sel && '✓'}
              </div>
              <div className={`w-2 h-2 rounded-full ${STATE_DOT[st]} ${st === 'online' ? 'animate-pulse' : ''}`} />
              <span className="text-sm font-semibold truncate flex-1">{d.name}</span>
              <span className={`text-[10px] font-bold ${STATE_TEXT[st]}`}>{STATE_LABEL[st]}</span>
              <button onClick={(e) => { e.stopPropagation(); renameDevice(d) }}
                className="text-gray-600 hover:text-gray-300 text-xs px-1">✎</button>
            </div>
          )
        })}
      </div>

      {/* Resumen de envío */}
      <div className={`rounded-xl px-3 py-2 mb-4 text-xs border ${
        selected.length > 0 ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-gray-900 border-gray-800 text-gray-500'}`}>
        {selected.length === 0
          ? 'Selecciona uno o más teléfonos para enviar órdenes'
          : `Enviando a ${selected.length} teléfono${selected.length > 1 ? 's' : ''}: ${selected.map(deviceName).join(', ')}`}
      </div>

      {/* ── TikTok ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">TIKTOK</p>
      <div className="grid grid-cols-2 gap-2 mb-4">
        <Btn onClick={() => sendCommand('TIKTOK_OPEN')}   disabled={off} color="bg-gray-700 hover:bg-gray-600">
          Abrir TikTok
        </Btn>
        <Btn onClick={() => sendCommand('TIKTOK_LIKE')}   disabled={off} color="bg-pink-700 hover:bg-pink-600">
          Like ♥
        </Btn>
        <Btn onClick={() => { const t = ask('Texto del comentario:'); if (t) sendCommand('TIKTOK_COMMENT', t) }}
          disabled={off} color="bg-cyan-700 hover:bg-cyan-600">
          Comentar 💬
        </Btn>
        <Btn onClick={() => sendCommand('TIKTOK_SAVE')}   disabled={off} color="bg-indigo-700 hover:bg-indigo-600">
          Guardar 🔖
        </Btn>
        <Btn onClick={() => sendCommand('SCROLL')}        disabled={off} color="bg-blue-700 hover:bg-blue-600" span>
          Scroll ↑
        </Btn>
      </div>

      {/* ── Cuentas TikTok ── */}
      <div className="flex items-center justify-between mb-2">
        <p className="text-xs text-gray-500 tracking-widest">CUENTAS</p>
        <button
          onClick={() => setFromLive(v => !v)}
          className={`text-xs px-2 py-1 rounded-lg border transition ${
            fromLive ? 'bg-rose-700 border-rose-600 text-white' : 'bg-gray-900 border-gray-700 text-gray-400'}`}
        >
          {fromLive ? '🔴 Desde live' : '○ Desde live'}
        </button>
      </div>
      <div className="bg-gray-900 rounded-xl border border-gray-800 p-3 mb-4">
        {singleSel ? (
          (singleSel.tiktok_accounts ?? []).length > 0 ? (
            <div className="grid grid-cols-2 gap-2 mb-2">
              {(singleSel.tiktok_accounts as string[]).map((acc) => (
                <Btn key={acc} onClick={() => sendCommand(fromLive ? 'TIKTOK_LIVE_SWITCH_ACCOUNT' : 'TIKTOK_SWITCH_ACCOUNT', acc)}
                  disabled={off} color="bg-yellow-700 hover:bg-yellow-600">
                  👤 {acc}
                </Btn>
              ))}
            </div>
          ) : (
            <p className="text-gray-600 text-xs mb-2">Sin cuentas en {singleSel.name} — toca Sincronizar</p>
          )
        ) : (
          <p className="text-gray-600 text-xs mb-2">Selecciona <b>un solo</b> teléfono para ver y cambiar sus cuentas</p>
        )}
        <button
          onClick={() => sendCommand('TIKTOK_GET_ACCOUNTS')}
          disabled={off}
          className="w-full text-xs text-gray-400 hover:text-gray-200 disabled:opacity-40 py-1.5 border border-gray-700 rounded-lg transition"
        >
          ↺ Sincronizar cuentas {selected.length > 1 ? `(${selected.length} teléfonos)` : 'del teléfono'}
        </button>
      </div>

      {/* ── TikTok Live ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">TIKTOK LIVE</p>
      <div className="flex gap-2 mb-2">
        <input
          type="text"
          value={liveLink}
          onChange={(e) => setLiveLink(e.target.value)}
          placeholder="Link del live (https://www.tiktok.com/@user/live)"
          className="flex-1 bg-gray-900 border border-gray-800 rounded-xl px-3 py-2 text-xs text-gray-200 placeholder-gray-600 focus:outline-none focus:border-gray-600"
        />
        <Btn onClick={() => { if (liveLink.trim()) sendCommand('TIKTOK_OPEN_LIVE', liveLink.trim()) }}
          disabled={off || !liveLink.trim()} color="bg-emerald-700 hover:bg-emerald-600">
          Abrir Live 📺
        </Btn>
      </div>
      <div className="grid grid-cols-2 gap-2 mb-4">
        <Btn onClick={() => { const t = ask('Comentario para el live:'); if (t) sendCommand('TIKTOK_LIVE_COMMENT', t) }}
          disabled={off} color="bg-rose-700 hover:bg-rose-600">
          Chat Live 🔴
        </Btn>
        <Btn onClick={() => { const g = ask('Nombre del regalo (ej: Rosa) — vacío = ver lista:'); sendCommand('TIKTOK_LIVE_GIFT', g ?? undefined) }}
          disabled={off} color="bg-fuchsia-700 hover:bg-fuchsia-600">
          Regalo 🎁
        </Btn>
      </div>

      {/* ── Debug / Control ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">CONTROL</p>
      <div className="grid grid-cols-3 gap-2 mb-4">
        <Btn onClick={() => sendCommand('DEBUG_NODES')} disabled={off} color="bg-gray-700 hover:bg-gray-600">
          Nodos 🔍
        </Btn>
        <Btn onClick={() => sendCommand('DEBUG_ALL')}   disabled={off} color="bg-gray-700 hover:bg-gray-600">
          Clicks 🔎
        </Btn>
        <Btn onClick={() => sendCommand('STOP')}        disabled={off} color="bg-red-800 hover:bg-red-700">
          STOP
        </Btn>
      </div>

      {/* Logs */}
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

    </main>
  )
}
