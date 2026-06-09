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

  const cmdInfo  = lastCmd ? CMD_STATUS[lastCmd.status] : null
  const isPaused = device?.status === 'paused'

  return (
    <main className="min-h-screen bg-gray-950 text-white p-4 font-mono max-w-lg mx-auto">
      <h1 className="text-xl font-bold mb-4">CONTROL BOT</h1>

      {/* Dispositivo */}
      <div className="bg-gray-900 rounded-xl p-3 mb-4 border border-gray-800">
        <p className="text-xs text-gray-500 mb-1">DISPOSITIVO</p>
        {device ? (
          <div className="flex items-center gap-3">
            <div className={`w-2.5 h-2.5 rounded-full ${STATUS_DOT[device.status] ?? 'bg-gray-400'} ${device.status === 'online' ? 'animate-pulse' : ''}`} />
            <span className="font-semibold text-sm">{device.name}</span>
            <span className={`text-xs font-bold ${device.status === 'paused' ? 'text-orange-400' : device.status === 'online' ? 'text-green-400' : 'text-gray-400'}`}>
              {STATUS_LABEL[device.status] ?? device.status.toUpperCase()}
            </span>
          </div>
        ) : (
          <p className="text-gray-500 text-xs">Sin dispositivo conectado</p>
        )}
      </div>

      {/* Banner de pausado */}
      {isPaused && (
        <div className="bg-orange-950 border border-orange-700 rounded-xl px-3 py-2 mb-4 text-orange-300 text-xs">
          El bot está pausado desde la app. Los comandos se enviarán cuando lo actives de nuevo.
        </div>
      )}

      {/* Estado del último comando */}
      <div className="bg-gray-900 rounded-xl p-3 mb-4 border border-gray-800 min-h-[52px]">
        <p className="text-xs text-gray-500 mb-1">ÚLTIMO COMANDO</p>
        {lastCmd && cmdInfo ? (
          <div>
            <span className={`text-sm font-semibold ${cmdInfo.color} ${cmdInfo.animate ? 'animate-pulse' : ''}`}>
              {cmdInfo.label}
            </span>
            <p className="text-xs text-gray-500 mt-0.5">
              {lastCmd.action}{lastCmd.payload ? ` : ${lastCmd.payload}` : ''}
              {lastCmd.executed_at && ` — ${new Date(lastCmd.executed_at).toLocaleTimeString()}`}
            </p>
          </div>
        ) : (
          <p className="text-gray-600 text-xs">Ninguno aún</p>
        )}
      </div>

      {/* Botones de comando */}
      <div className="grid grid-cols-2 gap-2 mb-4">
        <button onClick={() => sendCommand('WHATSAPP_TAB', 'Novedades')}
          disabled={sending || !device}
          className="bg-green-700 hover:bg-green-600 disabled:opacity-40 px-4 py-3 rounded-lg text-sm font-semibold transition col-span-2">
          WhatsApp → Novedades
        </button>
        <button onClick={() => sendCommand('TIKTOK_LIKE', '2')}
          disabled={sending || !device}
          className="bg-pink-700 hover:bg-pink-600 disabled:opacity-40 px-4 py-3 rounded-lg text-sm font-semibold transition">
          TikTok Like x2
        </button>
        <button onClick={() => sendCommand('SCROLL')}
          disabled={sending || !device}
          className="bg-blue-700 hover:bg-blue-600 disabled:opacity-40 px-4 py-3 rounded-lg text-sm font-semibold transition">
          SCROLL
        </button>
        <button onClick={() => sendCommand('STOP')}
          disabled={sending || !device}
          className="bg-red-700 hover:bg-red-600 disabled:opacity-40 px-4 py-3 rounded-lg text-sm font-semibold transition col-span-2">
          STOP
        </button>
      </div>

      {/* Logs */}
      <div className="bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
        <p className="text-xs text-gray-500 px-3 py-2 border-b border-gray-800">PASOS EN VIVO</p>
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
