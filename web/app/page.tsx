'use client'

import { useEffect, useState } from 'react'
import { supabase, Device, Log, Command } from '@/lib/supabase'

const CMD_STATUS: Record<string, { label: string; color: string; animate: boolean }> = {
  pending:   { label: '⏳ Enviado — esperando teléfono…', color: 'text-yellow-400', animate: true  },
  executing: { label: '▶️ Ejecutando en el teléfono…',   color: 'text-blue-400',   animate: true  },
  done:      { label: '✅ Completado',                    color: 'text-green-400',  animate: false },
  error:     { label: '❌ Error',                         color: 'text-red-400',    animate: false },
  cancelled: { label: '⛔ Cancelado',                     color: 'text-gray-500',   animate: false },
}

const STATUS_DOT: Record<string, string> = {
  online:  'bg-green-500',
  offline: 'bg-gray-500',
  busy:    'bg-yellow-400',
  error:   'bg-red-500',
  paused:  'bg-orange-400',
}

const STATUS_LABEL: Record<string, string> = {
  online:  'ACTIVO',
  offline: 'DESCONECTADO',
  busy:    'OCUPADO',
  error:   'ERROR',
  paused:  'PAUSADO',
}

const LOG_COLOR: Record<string, string> = {
  info:  'text-gray-300',
  warn:  'text-yellow-400',
  error: 'text-red-400',
}

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
  const [device, setDevice]   = useState<Device | null>(null)
  const [logs, setLogs]       = useState<Log[]>([])
  const [lastCmd, setLastCmd] = useState<Command | null>(null)
  const [sending, setSending] = useState(false)

  useEffect(() => {
    fetchDevice()
    fetchLogs()
    fetchLastCmd()

    const channel = supabase
      .channel('realtime-panel')
      .on('postgres_changes', { event: '*',      schema: 'public', table: 'devices'  }, () => fetchDevice())
      .on('postgres_changes', { event: '*',      schema: 'public', table: 'commands' }, () => fetchLastCmd())
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'logs'     }, (p) => {
        setLogs(prev => [p.new as Log, ...prev].slice(0, 50))
      })
      .subscribe()

    return () => { supabase.removeChannel(channel) }
  }, [])

  const fetchDevice = async () => {
    const { data } = await supabase.from('devices').select('*')
      .order('last_seen', { ascending: false }).limit(1).single()
    if (data) setDevice(data)
  }

  const fetchLogs = async () => {
    const { data } = await supabase.from('logs').select('*')
      .order('created_at', { ascending: false }).limit(50)
    if (data) setLogs(data)
  }

  const fetchLastCmd = async () => {
    const { data } = await supabase.from('commands').select('*')
      .order('created_at', { ascending: false }).limit(1).single()
    if (data) setLastCmd(data as Command)
  }

  const sendCommand = async (action: string, payload?: string) => {
    if (!device) return
    setSending(true)
    const { data } = await supabase.from('commands').insert({
      device_id: device.id, action, payload: payload ?? null, status: 'pending',
    }).select().single()
    if (data) setLastCmd(data as Command)
    setSending(false)
  }

  const ask = (label: string) => { const v = prompt(label); return v?.trim() || null }

  const cmdInfo  = lastCmd ? CMD_STATUS[lastCmd.status] : null
  const isPaused = device?.status === 'paused'
  const off      = sending || !device

  return (
    <main className="min-h-screen bg-gray-950 text-white p-4 font-mono max-w-lg mx-auto">

      <h1 className="text-lg font-bold tracking-widest mb-4 text-gray-100">BOTI CONTROL</h1>

      {/* Dispositivo */}
      <div className="bg-gray-900 rounded-xl p-3 mb-3 border border-gray-800">
        <p className="text-xs text-gray-500 mb-1">DISPOSITIVO</p>
        {device ? (
          <div className="flex items-center gap-3">
            <div className={`w-2 h-2 rounded-full ${STATUS_DOT[device.status] ?? 'bg-gray-400'} ${device.status === 'online' ? 'animate-pulse' : ''}`} />
            <span className="font-semibold text-sm">{device.name}</span>
            <span className={`text-xs font-bold ml-auto ${
              device.status === 'paused' ? 'text-orange-400' :
              device.status === 'online' ? 'text-green-400' : 'text-gray-400'}`}>
              {STATUS_LABEL[device.status] ?? device.status.toUpperCase()}
            </span>
          </div>
        ) : (
          <p className="text-gray-500 text-xs">Sin dispositivo conectado</p>
        )}
      </div>

      {isPaused && (
        <div className="bg-orange-950 border border-orange-800 rounded-xl px-3 py-2 mb-3 text-orange-300 text-xs">
          Bot pausado — actívalo desde la app para recibir comandos
        </div>
      )}

      {/* Último comando */}
      <div className="bg-gray-900 rounded-xl p-3 mb-4 border border-gray-800 min-h-[52px]">
        <p className="text-xs text-gray-500 mb-1">ÚLTIMO COMANDO</p>
        {lastCmd && cmdInfo ? (
          <div>
            <span className={`text-sm font-semibold ${cmdInfo.color} ${cmdInfo.animate ? 'animate-pulse' : ''}`}>
              {cmdInfo.label}
            </span>
            <p className="text-xs text-gray-600 mt-0.5">
              {lastCmd.action}{lastCmd.payload ? ` · ${lastCmd.payload}` : ''}
              {lastCmd.executed_at && ` · ${new Date(lastCmd.executed_at).toLocaleTimeString()}`}
            </p>
          </div>
        ) : (
          <p className="text-gray-600 text-xs">Ninguno aún</p>
        )}
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
      <p className="text-xs text-gray-500 mb-2 tracking-widest">CUENTAS</p>
      <div className="bg-gray-900 rounded-xl border border-gray-800 p-3 mb-4">
        {(device?.tiktok_accounts ?? []).length > 0 ? (
          <div className="grid grid-cols-2 gap-2 mb-2">
            {(device!.tiktok_accounts as string[]).map((acc) => (
              <Btn key={acc} onClick={() => sendCommand('TIKTOK_SWITCH_ACCOUNT', acc)}
                disabled={off} color="bg-yellow-700 hover:bg-yellow-600">
                👤 {acc}
              </Btn>
            ))}
          </div>
        ) : (
          <p className="text-gray-600 text-xs mb-2">Sin cuentas — toca Sincronizar para leerlas del teléfono</p>
        )}
        <button
          onClick={() => sendCommand('TIKTOK_GET_ACCOUNTS')}
          disabled={off}
          className="w-full text-xs text-gray-400 hover:text-gray-200 disabled:opacity-40 py-1.5 border border-gray-700 rounded-lg transition"
        >
          ↺ Sincronizar cuentas del teléfono
        </button>
      </div>

      {/* ── TikTok Live ── */}
      <p className="text-xs text-gray-500 mb-2 tracking-widest">TIKTOK LIVE</p>
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
            <p className="text-gray-600 text-xs">Aquí verás cada paso que hace el teléfono...</p>
          ) : (
            logs.map(log => (
              <div key={log.id} className={`text-xs ${LOG_COLOR[log.level] ?? 'text-gray-300'}`}>
                <span className="text-gray-600 mr-2">{new Date(log.created_at).toLocaleTimeString()}</span>
                {log.message}
              </div>
            ))
          )}
        </div>
      </div>

    </main>
  )
}
