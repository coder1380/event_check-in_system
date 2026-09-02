import { useEffect, useRef, useState, useCallback } from 'react'
import { Html5Qrcode } from 'html5-qrcode'
import { openDB } from 'idb'
import type { DBSchema, IDBPDatabase } from 'idb'
import toast from 'react-hot-toast'
import { ChevronRight, RefreshCw, Wifi, WifiOff, Volume2, VolumeX, CheckCircle, AlertOctagon, AlertTriangle, XCircle, Clock, Trash2 } from 'lucide-react'

interface ScannerDB extends DBSchema {
  pending_scans: {
    key: string
    value: {
      token: string
      station_id: string
      client_scanned_at: string
      queued_at: string
    }
  }
  synced_cache: {
    key: string
    value: {
      token: string
      outcome: string
      checked_in_at?: string | null
      station_id?: string | null
      synced_at: string
    }
  }
}

let dbPromise: Promise<IDBPDatabase<ScannerDB>> | null = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB<ScannerDB>('event_scanner_db', 1, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('pending_scans')) {
          db.createObjectStore('pending_scans', { keyPath: 'token' })
        }
        if (!db.objectStoreNames.contains('synced_cache')) {
          db.createObjectStore('synced_cache', { keyPath: 'token' })
        }
      },
    })
  }
  return dbPromise
}

async function getPendingScans() {
  try {
    const db = await getDB()
    return await db.getAll('pending_scans')
  } catch {
    return []
  }
}

async function addPendingScan(scan: { token: string; station_id: string; client_scanned_at: string; queued_at: string }) {
  try {
    const db = await getDB()
    const all = await db.getAll('pending_scans')
    if (all.length >= 500) {
      throw new Error('Queue full — sync required before scanning more attendees (500 max)')
    }
    await db.put('pending_scans', scan)
    return await db.getAll('pending_scans')
  } catch (e) {
    throw e
  }
}

async function removePendingScan(tokenStr: string) {
  try {
    const db = await getDB()
    await db.delete('pending_scans', tokenStr)
  } catch (e) {
    console.error('Failed to delete pending scan', e)
  }
}

async function addSyncedResult(result: { token: string; outcome: string; checked_in_at?: string | null; station_id?: string | null; synced_at: string }) {
  try {
    const db = await getDB()
    await db.put('synced_cache', result)
  } catch (e) {
    console.error('Failed to add synced result', e)
  }
}

async function getSyncedResult(tokenStr: string) {
  try {
    const db = await getDB()
    return await db.get('synced_cache', tokenStr)
  } catch {
    return undefined
  }
}

type ScanResultType = 'success' | 'duplicate' | 'expired' | 'invalid' | 'queued'

interface ScanResult {
  type: ScanResultType
  name?: string
  checked_in_at?: string
  station_id?: string
  message?: string
  pendingCount?: number
}

function formatTime(dateStr: string) {
  if (!dateStr) return ''
  try {
    const d = new Date(dateStr)
    if (isNaN(d.getTime())) return dateStr
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  } catch {
    return dateStr
  }
}

function playAudioFeedback(type: ScanResultType, soundEnabled = true) {
  if (!soundEnabled) return
  try {
    const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    if (!AudioCtx) return
    const ctx = new AudioCtx()
    const now = ctx.currentTime

    if (type === 'success') {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.setValueAtTime(523.25, now) // C5
      osc.frequency.setValueAtTime(783.99, now + 0.1) // G5
      gain.gain.setValueAtTime(0.35, now)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.3)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now)
      osc.stop(now + 0.3)
    } else if (type === 'duplicate') {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'triangle'
      osc.frequency.setValueAtTime(400, now)
      gain.gain.setValueAtTime(0.35, now)
      gain.gain.setValueAtTime(0.01, now + 0.1)
      gain.gain.setValueAtTime(0.35, now + 0.15)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.3)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now)
      osc.stop(now + 0.3)
    } else if (type === 'expired') {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.setValueAtTime(600, now)
      osc.frequency.exponentialRampToValueAtTime(300, now + 0.25)
      gain.gain.setValueAtTime(0.35, now)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now)
      osc.stop(now + 0.25)
    } else if (type === 'invalid') {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sawtooth'
      osc.frequency.setValueAtTime(160, now)
      gain.gain.setValueAtTime(0.35, now)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.35)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now)
      osc.stop(now + 0.35)
    } else if (type === 'queued') {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.setValueAtTime(440, now)
      gain.gain.setValueAtTime(0.25, now)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.2)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now)
      osc.stop(now + 0.2)
    }
  } catch {
    // Ignore audio context autoplay policy errors
  }
}

function triggerHapticFeedback(type: ScanResultType) {
  if (typeof window !== 'undefined' && 'vibrate' in navigator) {
    try {
      if (type === 'success') {
        navigator.vibrate(100)
      } else if (type === 'queued') {
        navigator.vibrate(60)
      } else {
        navigator.vibrate([120, 60, 120])
      }
    } catch {
      // Ignore vibration error
    }
  }
}

function ScanResultOverlay({ result, onDismiss }: { result: ScanResult; onDismiss: () => void }) {
  useEffect(() => {
    // Hook browser back button (popstate) so pressing Nav Back closes popup instead of leaving page
    window.history.pushState({ scanResultModal: true }, '')

    const handlePopState = () => {
      onDismiss()
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onDismiss()
      }
    }

    window.addEventListener('popstate', handlePopState)
    window.addEventListener('keydown', handleKeyDown)

    return () => {
      window.removeEventListener('popstate', handlePopState)
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [onDismiss])

  let bg = 'bg-emerald-600 border-emerald-400'
  let icon = '✅'
  let badge = 'SUCCESS — ENTRY GRANTED'
  let title = `Checked in — ${result.name || 'Guest'}`
  let detail = 'Attendee check-in accepted by server.'

  if (result.type === 'duplicate') {
    bg = 'bg-amber-600 border-amber-400'
    icon = '⛔'
    badge = 'ALREADY CHECKED IN'
    const timeText = result.checked_in_at ? formatTime(result.checked_in_at) : ''
    const stationText = result.station_id ? ` (${result.station_id})` : ''
    title = 'Ticket Already Scanned'
    detail = `Checked in ${timeText ? `at ${timeText}` : 'earlier'}${stationText}`
  } else if (result.type === 'expired') {
    bg = 'bg-amber-600 border-amber-400'
    icon = '⚠️'
    badge = 'EXPIRED QR PASS'
    title = 'Pass Expired'
    detail = 'Ask attendee to refresh their QR pass on their phone.'
  } else if (result.type === 'invalid') {
    bg = 'bg-rose-600 border-rose-400'
    icon = '❌'
    badge = 'INVALID SCAN'
    title = 'Scan Rejected'
    detail = result.message || 'Invalid QR code'
  } else if (result.type === 'queued') {
    bg = 'bg-blue-600 border-blue-400'
    icon = '🟡'
    badge = 'QUEUED OFFLINE'
    title = 'Saved Locally'
    detail = `Scan queued offline (${result.pendingCount || 1} pending sync)`
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-md animate-in fade-in duration-200">
      <div className={`relative w-full max-w-md rounded-[2.5rem] ${bg} border-4 p-8 text-white shadow-2xl flex flex-col items-center text-center space-y-6`}>
        <div className="w-full flex justify-end">
          <button
            onClick={onDismiss}
            className="rounded-full bg-black/20 hover:bg-black/40 text-white p-2 transition-colors"
            title="Close popup (Esc / Nav Back)"
          >
            ✕
          </button>
        </div>

        <div className="flex flex-col items-center">
          <span className="text-7xl mb-4 animate-bounce">{icon}</span>
          <span className="text-xs font-black uppercase tracking-widest bg-black/30 px-4 py-1.5 rounded-full mb-3">
            {badge}
          </span>
          <h2 className="text-2xl md:text-3xl font-black leading-tight mb-2">{title}</h2>
          <p className="text-sm font-semibold opacity-90 leading-relaxed max-w-xs">{detail}</p>
        </div>

        <button
          onClick={onDismiss}
          className="w-full py-4 px-6 rounded-2xl bg-white text-slate-900 font-extrabold text-base hover:bg-slate-100 transition-colors shadow-lg active:scale-95"
        >
          Scan Next Ticket →
        </button>

        <p className="text-xs opacity-60 font-semibold">Press Esc or Nav Back to return to scanner</p>
      </div>
    </div>
  )
}

// Camera scanner singleton and lifecycle guard.
// Html5Qrcode is stateful: start() renders a live <video> into the #reader
// element and keeps the camera stream open until stop()/clear(). React
// StrictMode (enabled in main.tsx) double-mounts effects in dev, and route
// navigation can remount this component. Because start() is async (it awaits
// camera access before rendering), two quick mounts could both end up rendering
// their viewfinder into the same #reader div (the duplicate-widget bug), and
// the instance that loses the race keeps its camera stream running forever.
//
// Fix: keep a single module-level Html5Qrcode instance, serialize starts
// through scannerStartInFlight, and stamp every start with an epoch. On unmount
// we bump the epoch; any in-flight start() that belongs to a dead mount stops
// its own stream (stop + clear) as soon as the camera becomes available instead
// of rendering a second viewfinder and leaking the old one.
let sharedScanner: Html5Qrcode | null = null
let scannerStartInFlight: Promise<void> | null = null
let scannerEpoch = 0

function CameraScannerComponent({ onScan }: { onScan: (decodedText: string) => Promise<void> }) {
  const scanLockRef = useRef(false)
  const onScanRef = useRef(onScan)
  const [cameraError, setCameraError] = useState<string | null>(null)
  const [isRequesting, setIsRequesting] = useState(false)

  useEffect(() => {
    onScanRef.current = onScan
  }, [onScan])

  const startScanner = useCallback(async () => {
    // Serialize concurrent starts (StrictMode double-effect, remounts, retry
    // clicks). Wait for the previous start to fully settle so two
    // Html5Qrcode.start() calls can never race against the same #reader div.
    while (scannerStartInFlight) {
      try {
        await scannerStartInFlight
      } catch {
        // A failed start still resolves; keep waiting only while one is in flight.
      }
    }

    const run = (async () => {
      const myEpoch = scannerEpoch
      setIsRequesting(true)
      setCameraError(null)

      try {
        const container = document.getElementById('reader')
        if (!container) return

        // Tear down any previous stream before restarting; a second <video> must
        // never be layered on top of a live one.
        if (sharedScanner) {
          try {
            if (sharedScanner.isScanning) await sharedScanner.stop()
          } catch {
            // stop() throws when the scanner is not running yet.
          }
          try {
            sharedScanner.clear()
          } catch {
            // clear() throws if a scan still owns the element.
          }
        }

        container.innerHTML = ''

        // The singleton: created once, reused everywhere. Html5Qrcode re-looks
        // up #reader by id on every start(), so reuse stays correct even when
        // React replaced the div between mounts.
        const qrScanner = (sharedScanner ||= new Html5Qrcode('reader'))

        const handleScan = async (decodedText: string) => {
          if (scanLockRef.current) return
          scanLockRef.current = true

          try {
            if (onScanRef.current) {
              await onScanRef.current(decodedText)
            }
          } finally {
            setTimeout(() => {
              scanLockRef.current = false
            }, 2000)
          }
        }

        const config = { fps: 10, qrbox: { width: 250, height: 250 } }
        const isStale = () => myEpoch !== scannerEpoch || sharedScanner !== qrScanner

        const attemptStart = async (cameraIdOrConfig: string | MediaTrackConstraints | boolean) => {
          if (isStale()) return false
          try {
            await qrScanner.start(
              cameraIdOrConfig as string | MediaTrackConstraints,
              config,
              handleScan,
              () => {},
            )
            // Our mount may have been torn down while the camera was warming up.
            // If so, stop the freshly started stream immediately rather than
            // leaking it or letting it render a second viewfinder.
            if (isStale()) {
              try {
                if (qrScanner.isScanning) await qrScanner.stop()
              } catch {
                // stop() can throw if the stream is already gone.
              }
              try { qrScanner.clear() } catch { /* element may already be detached */ }
              return false
            }
            return true
          } catch {
            return false
          }
        }

        // Request camera permission up front. Tying getUserMedia to the button
        // click (a user gesture) makes the browser prompt appear reliably; the
        // following start() calls then reuse the granted stream.
        if (navigator.mediaDevices?.getUserMedia) {
          try {
            await navigator.mediaDevices.getUserMedia({ video: true })
          } catch {
            // No permission / no camera. attemptStart will surface the error.
          }
        }

        // Rear camera first, then front, then the browser default camera.
        const started =
          (await attemptStart({ facingMode: 'environment' })) ||
          (await attemptStart({ facingMode: 'user' })) ||
          (await attemptStart(true))

        if (!started && !isStale()) {
          setCameraError('Camera access failed or permission was denied. Click below to try again.')
        }
      } finally {
        // Always re-enable the retry button even when every camera attempt
        // failed, otherwise it stays disabled forever after a single attempt.
        setIsRequesting(false)
      }
    })()

    scannerStartInFlight = run
    try {
      await run
    } catch {
      // startScanner never rejects; this guards an unexpected throw above so it
      // cannot become an unhandled rejection from the effect.
    } finally {
      scannerStartInFlight = null
    }
  }, [])

  useEffect(() => {
    startScanner()

    return () => {
      // Invalidate in-flight starts owned by this mount; they self-stop as soon
      // as their camera becomes available (see the staleness check), so no
      // stream is orphaned.
      scannerEpoch++
      const scanner = sharedScanner
      if (scanner?.isScanning) {
        scanner.stop()
          .then(() => {
            try { scanner.clear() } catch { /* element may already be gone */ }
          })
          .catch(() => { /* stop() rejection is safe to ignore here */ })
      }
      const el = document.getElementById('reader')
      if (el) el.innerHTML = ''
    }
  }, [startScanner])

  return (
    <div className="relative w-full min-h-[320px] rounded-[2rem] overflow-hidden bg-slate-950 flex flex-col items-center justify-center">
      <div id="reader" className="w-full h-full min-h-[320px]" />
      {cameraError && (
        <div className="absolute inset-0 bg-slate-900 flex flex-col items-center justify-center p-6 text-center text-white z-20">
          <span className="text-4xl mb-3">📷</span>
          <p className="text-base font-bold text-rose-300 mb-2">Camera Permission Required</p>
          <p className="text-xs text-slate-300 mb-5 max-w-sm">
            {cameraError} Click below to prompt your browser for camera access.
          </p>
          <button
            type="button"
            onClick={startScanner}
            disabled={isRequesting}
            className="button button-primary font-bold px-6 py-3 rounded-xl shadow-lg flex items-center gap-2 text-sm"
          >
            {isRequesting ? 'Requesting Access…' : 'Allow Camera Access'}
          </button>
        </div>
      )}
    </div>
  )
}

export function CameraScanner({
  token,
  station: initialStation,
}: {
  token: string
  station?: string
}) {
  const [station, setStation] = useState(() => initialStation || localStorage.getItem('scanner_station') || '')
  const [input, setInput] = useState('')
  const [result, setResult] = useState<ScanResult | null>(null)
  const [isOnline, setIsOnline] = useState(navigator.onLine)
  const [pendingCount, setPendingCount] = useState(0)
  const [syncing, setSyncing] = useState(false)
  const [syncLog, setSyncLog] = useState<Array<{ token: string; outcome: string; station_id?: string | null; checked_in_at?: string | null }>>([])
  const [soundEnabled, setSoundEnabled] = useState(() => {
    const saved = localStorage.getItem('scanner_sound_enabled')
    return saved === null ? true : saved === 'true'
  })
  const [recentScans, setRecentScans] = useState<Array<{
    id: string
    timestamp: string
    type: ScanResultType
    title: string
    detail: string
    tokenSnippet: string
  }>>([])

  const toggleSound = () => {
    setSoundEnabled((prev) => {
      const next = !prev
      localStorage.setItem('scanner_sound_enabled', String(next))
      return next
    })
  }

  const loadPendingCount = useCallback(async () => {
    const pending = await getPendingScans()
    setPendingCount(pending.length)
  }, [])

  useEffect(() => {
    loadPendingCount()
  }, [loadPendingCount])

  // Sync handler
  const triggerSync = useCallback(async () => {
    if (syncing) return
    const pending = await getPendingScans()
    if (pending.length === 0) return

    setSyncing(true)
    try {
      const response = await fetch(`${import.meta.env.VITE_API_URL || ''}/api/v1/checkins/sync-batch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          station_id: station,
          scans: pending.map((item) => ({
            token: item.token,
            client_scanned_at: item.client_scanned_at,
          })),
        }),
      })

      if (response.ok) {
        const body = await response.json()
        const results: Array<{ token: string; outcome: string; checked_in_at?: string; station_id?: string }> = body.results || []

        let acceptedCount = 0
        const logEntries: typeof syncLog = []

        for (let i = 0; i < results.length; i++) {
          const res = results[i]
          const original = pending[i]
          if (original) {
            await removePendingScan(original.token)
            await addSyncedResult({
              token: original.token,
              outcome: res.outcome,
              checked_in_at: res.checked_in_at ?? null,
              station_id: res.station_id ?? null,
              synced_at: new Date().toISOString(),
            })
            logEntries.push({
              token: original.token,
              outcome: res.outcome,
              checked_in_at: res.checked_in_at ?? null,
              station_id: res.station_id ?? null,
            })
          }

          if (res.outcome === 'accepted') {
            acceptedCount++
          } else if (res.outcome === 'rejected_duplicate') {
            const stationStr = res.station_id ? ` by Station ${res.station_id}` : ''
            const timeStr = res.checked_in_at ? ` at ${formatTime(res.checked_in_at)}` : ''
            toast.error(`Offline scan duplicate: Already checked in${stationStr}${timeStr}`, { duration: 5000 })
          }
        }

        setSyncLog(logEntries)
        if (acceptedCount > 0) {
          toast.success(`Synced ${acceptedCount} offline scan(s) successfully!`)
        }
      }
    } catch (e) {
      toast.error('Sync failed: Network still unreachable')
    } finally {
      setSyncing(false)
      await loadPendingCount()
    }
  }, [token, station, syncing, loadPendingCount])

  // Online / Offline & Sync listeners
  useEffect(() => {
    const handleOnline = () => {
      setIsOnline(true)
      triggerSync()
    }
    const handleOffline = () => setIsOnline(false)

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    const timer = setInterval(() => {
      if (navigator.onLine && pendingCount > 0) {
        triggerSync()
      }
    }, 30000)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
      clearInterval(timer)
    }
  }, [pendingCount, triggerSync])

  async function processToken(decodedToken: string) {
    const trimmed = decodedToken.trim()
    if (!trimmed) return

    const scannedAt = new Date().toISOString()

    const recordScanOutcome = (res: ScanResult) => {
      setResult(res)
      playAudioFeedback(res.type, soundEnabled)
      triggerHapticFeedback(res.type)

      let title = ''
      let detail = ''

      if (res.type === 'success') {
        title = `Checked in — ${res.name || 'Guest'}`
        detail = `Accepted by server (${station})`
        toast.success(`✅ ${title}`, { duration: 4000 })
      } else if (res.type === 'duplicate') {
        const timeText = res.checked_in_at ? formatTime(res.checked_in_at) : ''
        const stationText = res.station_id ? ` (${res.station_id})` : ''
        title = 'Already checked in'
        detail = `Used ${timeText ? `at ${timeText}` : 'earlier'}${stationText}`
        toast.error(`⛔ Already checked in${timeText ? ` at ${timeText}` : ''}${stationText}`, { duration: 5000 })
      } else if (res.type === 'expired') {
        title = 'Expired QR pass'
        detail = 'Ask attendee to refresh their QR'
        toast.error(`⚠️ ${title} — Ask attendee to refresh QR`, { duration: 5000 })
      } else if (res.type === 'invalid') {
        title = res.message || 'Invalid QR code'
        detail = 'Code invalid or unrecognized'
        toast.error(`❌ ${title}`, { duration: 5000 })
      } else if (res.type === 'queued') {
        title = 'Queued offline'
        detail = `${res.pendingCount || 1} pending sync`
        toast(`🟡 Queued offline (${res.pendingCount || 1} pending)`, { duration: 4000, icon: '🟡' })
      }

      setRecentScans((prev) => [
        {
          id: Math.random().toString(36).substring(2, 9),
          timestamp: new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' }),
          type: res.type,
          title,
          detail,
          tokenSnippet: trimmed.length > 14 ? `${trimmed.slice(0, 12)}…` : trimmed,
        },
        ...prev.slice(0, 14),
      ])
    }

    // Check local synced cache first
    const syncedLocal = await getSyncedResult(trimmed)
    if (syncedLocal) {
      recordScanOutcome({
        type: 'duplicate',
        checked_in_at: syncedLocal.checked_in_at || undefined,
        station_id: syncedLocal.station_id || undefined,
      })
      return
    }

    try {
      const response = await fetch(`${import.meta.env.VITE_API_URL || ''}/api/v1/checkins`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ token: trimmed, station_id: station }),
      })

      const body = await response.json()

      if (response.ok) {
        recordScanOutcome({ type: 'success', name: body.checkin?.attendee_name || 'Guest' })
      } else if (response.status === 409 && body.error?.code === 'ALREADY_CHECKED_IN') {
        const message = body.error?.message || 'Already checked in'
        const isoMatch = message.match(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?/i)
        const stationMatch = message.match(/\(([^)]+)\)/)
        recordScanOutcome({
          type: 'duplicate',
          checked_in_at: isoMatch ? isoMatch[0] : (body.existing?.checked_in_at || message),
          station_id: stationMatch ? stationMatch[1] : (body.existing?.station_id || undefined),
        })
      } else if (response.status === 410 && body.error?.code === 'TOKEN_EXPIRED') {
        recordScanOutcome({ type: 'expired' })
      } else if (response.status === 404 && body.error?.code === 'TOKEN_INVALID') {
        recordScanOutcome({ type: 'invalid', message: 'Invalid QR code' })
      } else {
        recordScanOutcome({ type: 'invalid', message: body.error?.message || 'Scan failed' })
      }
    } catch (e) {
      // Network failure -> queue locally if well-formed
      if (trimmed.length > 5) {
        try {
          const updatedList = await addPendingScan({
            token: trimmed,
            station_id: station,
            client_scanned_at: scannedAt,
            queued_at: scannedAt,
          })
          setPendingCount(updatedList.length)
          recordScanOutcome({ type: 'queued', pendingCount: updatedList.length })
          if (updatedList.length >= 400) {
            toast.error(`Queue high (${updatedList.length}/500 pending). Please connect to sync!`)
          }
        } catch (queueErr) {
          recordScanOutcome({ type: 'invalid', message: (queueErr as Error).message || 'Failed to queue scan' })
        }
      } else {
        recordScanOutcome({ type: 'invalid', message: 'Malformed scan' })
      }
    }
  }

  const handleCameraScan = useCallback(async (decodedText: string) => {
    if (result) return
    await processToken(decodedText)
  }, [station, token, result])

  if (!station) {
    return (
      <div className="min-h-[calc(100vh-4rem)] bg-ink px-5 py-16 text-white">
        <div className="mx-auto max-w-md">
          <p className="eyebrow text-brand-300">Scanner setup</p>
          <h1 className="mt-3 font-display text-4xl font-extrabold">Name this station.</h1>
          <p className="mt-4 text-slate-400">Give this scanner a simple ID so the event record stays useful.</p>
          <form
            className="mt-8 space-y-4"
            onSubmit={(e) => {
              e.preventDefault()
              if (input.trim()) {
                localStorage.setItem('scanner_station', input.trim())
                setStation(input.trim())
              }
            }}
          >
            <input className="input" value={input} onChange={(e) => setInput(e.target.value)} placeholder="scanner-1" />
            <button className="button button-primary w-full" disabled={!input.trim()}>
              Start scanning <ChevronRight size={16} />
            </button>
          </form>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-ink px-5 py-10 text-white">
      <div className="mx-auto max-w-2xl">
        {/* Top status bar */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <p className="eyebrow text-brand-300">Door scanner</p>
            <h1 className="mt-2 font-display text-3xl font-extrabold">{station}</h1>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={toggleSound}
              className={`flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-bold border transition-colors ${
                soundEnabled
                  ? 'bg-slate-800 text-emerald-300 border-emerald-500/30 hover:bg-slate-700'
                  : 'bg-slate-800/60 text-slate-400 border-white/10 hover:bg-slate-800'
              }`}
              title={soundEnabled ? 'Mute scanner audio feedback' : 'Unmute scanner audio feedback'}
            >
              {soundEnabled ? <Volume2 size={14} className="text-emerald-400" /> : <VolumeX size={14} className="text-slate-400" />}
              <span>{soundEnabled ? 'Sound On' : 'Muted'}</span>
            </button>
            {pendingCount > 0 && (
              <button
                onClick={triggerSync}
                disabled={syncing || !isOnline}
                className="flex items-center gap-2 rounded-xl bg-brand-500/20 px-3 py-1.5 text-xs font-bold text-brand-300 border border-brand-500/30 hover:bg-brand-500/30 disabled:opacity-50"
              >
                <RefreshCw size={14} className={syncing ? 'animate-spin' : ''} />
                <span>{pendingCount} Pending</span>
              </button>
            )}
            <span className={`status ${isOnline ? 'status-online' : 'status-offline'} flex items-center gap-1.5`}>
              {isOnline ? <Wifi size={14} className="text-emerald-400" /> : <WifiOff size={14} className="text-rose-400" />}
              {isOnline ? 'Online' : 'Offline'}
            </span>
          </div>
        </div>

        {/* Offline Warning Banner */}
        {!isOnline && (
          <div className="mb-6 rounded-2xl bg-amber-500/15 border border-amber-500/30 p-4 text-amber-200 text-sm font-semibold flex items-center justify-between">
            <span>Offline — scanning will queue locally and sync when reconnected.</span>
            {pendingCount > 0 && <span className="bg-amber-500 text-black px-2 py-0.5 rounded-full text-xs font-bold">{pendingCount} queued</span>}
          </div>
        )}

        {/* Queue high warning (400+) */}
        {pendingCount >= 400 && (
          <div className="mb-4 rounded-2xl bg-red-500/15 border border-red-500/40 p-4 text-red-300 text-sm font-semibold">
            ⚠️ Queue high ({pendingCount}/500) — connect to sync before scanning more attendees.
          </div>
        )}

        {/* Sync in-flight indicator */}
        {syncing && (
          <div className="mb-4 rounded-2xl bg-brand-500/10 border border-brand-500/30 p-3 text-brand-300 text-sm font-semibold flex items-center gap-2">
            <RefreshCw size={14} className="animate-spin" />
            Syncing {pendingCount} offline scan(s)…
          </div>
        )}

        {/* Per-item sync results log */}
        {syncLog.length > 0 && !syncing && (
          <div className="mb-4 rounded-2xl border border-white/10 bg-slate-800 p-4">
            <p className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-3">Last sync results</p>
            <ul className="space-y-2">
              {syncLog.map((entry) => (
                <li key={entry.token} className="flex items-start gap-2 text-sm">
                  {entry.outcome === 'accepted' ? (
                    <span className="text-emerald-400 font-bold">✅ Checked in</span>
                  ) : (
                    <span className="text-amber-400 font-bold">
                      ⛔ Already checked in{entry.station_id ? ` by Station ${entry.station_id}` : ''}{entry.checked_in_at ? ` at ${formatTime(entry.checked_in_at)}` : ''}
                    </span>
                  )}
                  <span className="text-slate-500 text-xs font-mono truncate max-w-[160px]">{entry.token.slice(0, 12)}…</span>
                </li>
              ))}
            </ul>
            <button className="mt-3 text-xs text-slate-500 hover:text-slate-300" onClick={() => setSyncLog([])}>
              Dismiss
            </button>
          </div>
        )}

        {/* Camera scanner area */}
        <div className="mt-4 rounded-[2rem] border border-white/10 bg-slate-900 p-6 overflow-hidden relative">
          <CameraScannerComponent onScan={handleCameraScan} />
          {result && <ScanResultOverlay result={result} onDismiss={() => setResult(null)} />}
        </div>

        {/* Recent Scans Live Activity Log */}
        {recentScans.length > 0 && (
          <div className="mt-6 rounded-2xl border border-white/10 bg-slate-800/80 p-5">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <Clock size={16} className="text-brand-300" />
                <h3 className="text-xs font-bold uppercase tracking-wider text-slate-300">Live Scan Feedback Feed</h3>
              </div>
              <button
                onClick={() => setRecentScans([])}
                className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1 transition-colors"
              >
                <Trash2 size={12} /> Clear history
              </button>
            </div>
            <div className="space-y-2.5 max-h-60 overflow-y-auto pr-1">
              {recentScans.map((item) => (
                <div key={item.id} className="flex items-center justify-between rounded-xl bg-slate-900/80 p-3 text-sm border border-white/5">
                  <div className="flex items-center gap-3 min-w-0">
                    {item.type === 'success' && <span className="rounded-full bg-emerald-500/20 text-emerald-400 p-1"><CheckCircle size={16} /></span>}
                    {item.type === 'duplicate' && <span className="rounded-full bg-amber-500/20 text-amber-400 p-1"><AlertOctagon size={16} /></span>}
                    {item.type === 'expired' && <span className="rounded-full bg-amber-400/20 text-amber-300 p-1"><AlertTriangle size={16} /></span>}
                    {item.type === 'invalid' && <span className="rounded-full bg-rose-500/20 text-rose-400 p-1"><XCircle size={16} /></span>}
                    {item.type === 'queued' && <span className="rounded-full bg-blue-500/20 text-blue-400 p-1"><Clock size={16} /></span>}
                    <div className="min-w-0">
                      <p className="font-bold text-slate-200 truncate">{item.title}</p>
                      <p className="text-xs text-slate-400 truncate">{item.detail}</p>
                    </div>
                  </div>
                  <div className="text-right pl-3 flex-shrink-0">
                    <span className="text-xs text-slate-400 font-mono block">{item.timestamp}</span>
                    <span className="text-[10px] text-slate-500 font-mono block truncate">{item.tokenSnippet}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

