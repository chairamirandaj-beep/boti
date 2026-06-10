import { createClient } from '@supabase/supabase-js'

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL!
const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!

export const supabase = createClient(supabaseUrl, supabaseAnonKey)

export type DeviceStatus = 'online' | 'offline' | 'busy' | 'error' | 'paused'

export interface Device {
  id: string
  name: string
  status: DeviceStatus
  last_seen: string | null
  battery: number | null
  connected: boolean
  tiktok_accounts: string[] | null
}

export interface Command {
  id: string
  device_id: string
  action: string
  payload: string | null
  status: 'pending' | 'executing' | 'done' | 'error' | 'cancelled'
  created_at: string
  executed_at: string | null
}

export interface Log {
  id: string
  device_id: string
  level: 'info' | 'warn' | 'error'
  message: string
  created_at: string
}
