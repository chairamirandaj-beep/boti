'use client'

import { useEffect, useState } from 'react'
import { supabase, Device, Log } from '@/lib/supabase'

export default function Home() {
  const [device, setDevice] = useState<Device | null>(null)
  const [logs, setLogs] = useState<Log[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    fetchDevice()
    fetchLogs()

    const channel = supabase
      .channel('realtime-panel')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'devices' }, () => fetchDevice())
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'logs' }, (payload) => {
        setLogs(prev => [payload.new as Log, ...prev].slice(0, 50))
      })
      .subscribe()

    return () => { supabase.removeChannel(channel) }
  }, [])

  const fetchDevice = async () => {
    const { data } = await supabase
      .from('devices')
      .select('*')
      .order('last_seen', { ascending: false })
      .limit(1)
      .single()
    if (data) setDevice(data)
  }

  const fetchLogs = async () => {
    const { data } = await supabase
      .from('logs')
      .select('*')
      .order('created_at', { ascending: false })
      .limit(50)
    if (data) setLogs(data)
  }

  const sendCommand = async (action: string, payload?: string) => {
    if (!device) return
    setLoading(true)
    await supabase.from('commands').insert({
      device_id: device.id,
      action,
      payload: payload ?? null,
      status: 'pending',
    })
    setLoading(false)
  }

  const statusColor: Record<string, string> = {
    online: 'bg-green-500',
    offline: 'bg-gray-400',
    busy: 'bg-yellow-400',
    error: 'bg-red-500',
  }

  const logColor: Record<string, string> = {
    info: 'text-gray-300',
    warn: 'text-yellow-400',
    error: 'text-red-400',
  }

  return (
    <main className="min-h-screen bg-gray-950 text-white p-6 font-mono">
      <h1 className="text-2xl font-bold mb-6">CONTROL BOT</h1>

      <div className="bg-gray-900 rounded-xl p-4 mb-6 border border-gray-800">
        <p className="text-sm text-gray-400 mb-2">DISPOSITIVO</p>
        {device ? (
          <div className="flex items-center gap-4">
            <div className={`w-3 h-3 rounded-full ${statusColor[device.status] ?? 'bg-gray-400'}`} />
            <span className="font-semibold">{device.name}</span>
            <span className="text-gray-400 text-sm">{device.status.toUpperCase()}</span>
            {device.battery !== null && (
              <span className="text-gray-400 text-sm ml-auto">BAT {device.battery}%</span>
            )}
          </div>
        ) : (
          <p className="text-gray-500 text-sm">Sin dispositivo conectado</p>
        )}
      </div>

      <div className="flex flex-wrap gap-3 mb-6">
        <button
          onClick={() => sendCommand('TIKTOK_LIKE', '2')}
          disabled={loading || !device}
          className="bg-pink-600 hover:bg-pink-500 disabled:opacity-40 px-6 py-3 rounded-lg font-semibold transition"
        >
          TikTok Like x2
        </button>
        <button
          onClick={() => sendCommand('SCROLL')}
          disabled={loading || !device}
          className="bg-blue-600 hover:bg-blue-500 disabled:opacity-40 px-6 py-3 rounded-lg font-semibold transition"
        >
          SCROLL
        </button>
        <button
          onClick={() => sendCommand('STOP')}
          disabled={loading || !device}
          className="bg-red-600 hover:bg-red-500 disabled:opacity-40 px-6 py-3 rounded-lg font-semibold transition"
        >
          STOP
        </button>
      </div>

      <div className="bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
        <div className="px-4 py-2 border-b border-gray-800">
          <p className="text-sm text-gray-400">LOGS EN VIVO</p>
        </div>
        <div className="h-64 overflow-y-auto p-4 space-y-1">
          {logs.length === 0 ? (
            <p className="text-gray-600 text-sm">Sin logs todavía...</p>
          ) : (
            logs.map(log => (
              <div key={log.id} className={`text-sm ${logColor[log.level] ?? 'text-gray-300'}`}>
                <span className="text-gray-600 mr-2">
                  {new Date(log.created_at).toLocaleTimeString()}
                </span>
                {log.message}
              </div>
            ))
          )}
        </div>
      </div>
    </main>
  )
}
