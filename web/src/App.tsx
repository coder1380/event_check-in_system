import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import { io } from 'socket.io-client'
import QRCode from 'qrcode'
import { CalendarDays, Check, ChevronRight, CircleUserRound, Download, LayoutDashboard, LogOut, Moon, Plus, QrCode, ScanLine, Search, Sun, Ticket } from 'lucide-react'
import toast, { Toaster } from 'react-hot-toast'
import { CameraScanner } from './CameraScanner'
import './index.css'

type Role = 'organizer' | 'attendee'
type User = { id: string; email: string; full_name: string; role: Role }
type EventItem = { id: string; name: string; event_date: string; capacity: number; registered_count: number; spots_remaining: number }
type Registration = { id: string; event_id: string; event_name: string; event_date: string; status: string; checked_in_at?: string | null }
type Dashboard = { event_id: string; capacity: number; registered_count: number; checked_in_count: number; spots_remaining: number; attendees: { registration_id: string; name: string; checked_in_at?: string | null }[] }

const API = import.meta.env.VITE_API_URL || ''
// const API = 'https://wool-refuse-anthem.ngrok-free.dev'
console.log("API URL:", import.meta.env.VITE_API_URL);
const dateLabel = (date: string) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(date))
const toDateInput = (date: string) => { const value = new Date(date); const offset = value.getTimezoneOffset(); return new Date(value.getTime() - offset * 60000).toISOString().slice(0, 16) }
const messageFor = (body: { error?: { message?: string } }) => body?.error?.message || 'Something went wrong. Please try again.'

export class ApiError extends Error {
    status: number
    code?: string
    expires_in?: number
    expires_at?: string
    constructor(message: string, status: number, body?: any) {
        super(message)
        this.status = status
        this.code = body?.error?.code
        this.expires_in = body?.error?.expires_in
        this.expires_at = body?.error?.expires_at
    }
}

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
    const currentToken = token || localStorage.getItem('access_token') || ''
    const response = await fetch(`${API}/api/v1${path}`, {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            ...(currentToken ? { Authorization: `Bearer ${currentToken}` } : {}),
            ...options.headers,
        },
    })

    const body = await response.json().catch(() => ({}))

    if (!response.ok) {
        if ((response.status === 401 || response.status === 403) && !path.includes('/auth/login') && !path.includes('/auth/register')) {
            const refreshToken = localStorage.getItem('refresh_token')
            if (refreshToken && !path.includes('/auth/refresh')) {
                try {
                    const refreshRes = await fetch(`${API}/api/v1/auth/refresh`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ refresh_token: refreshToken }),
                    })
                    if (refreshRes.ok) {
                        const refreshBody = await refreshRes.json()
                        localStorage.setItem('access_token', refreshBody.access_token)
                        if (refreshBody.refresh_token) localStorage.setItem('refresh_token', refreshBody.refresh_token)
                        const retryRes = await fetch(`${API}/api/v1${path}`, {
                            ...options,
                            headers: {
                                'Content-Type': 'application/json',
                                Authorization: `Bearer ${refreshBody.access_token}`,
                                ...options.headers,
                            },
                        })
                        const retryBody = await retryRes.json().catch(() => ({}))
                        if (retryRes.ok) return retryBody as T
                    }
                } catch {}
            }
            window.dispatchEvent(new Event('auth:unauthorized'))
        }
        throw new ApiError(messageFor(body), response.status, body)
    }
    return body as T
}

type AuthValue = { user: User | null; token: string | null; signIn: (data: { user: User; access_token: string; refresh_token?: string }) => void; signOut: () => void }
const AuthContext = createContext<AuthValue | null>(null)
function useAuth() { const value = useContext(AuthContext); if (!value) throw new Error('AuthProvider is required'); return value }
function AuthProvider({ children }: { children: ReactNode }) {
    const [token, setToken] = useState(() => localStorage.getItem('access_token'))
    const [user, setUser] = useState<User | null>(() => { const raw = localStorage.getItem('user'); return raw ? JSON.parse(raw) : null })
    const signIn = (data: { user: User; access_token: string; refresh_token?: string }) => {
        setToken(data.access_token);
        setUser(data.user);
        localStorage.setItem('access_token', data.access_token);
        localStorage.setItem('user', JSON.stringify(data.user));
        if (data.refresh_token) localStorage.setItem('refresh_token', data.refresh_token);
    }
    const signOut = useCallback(() => {
        setToken(null);
        setUser(null);
        localStorage.removeItem('access_token');
        localStorage.removeItem('refresh_token');
        localStorage.removeItem('user');
    }, [])

    useEffect(() => {
        const handleUnauthorized = () => signOut()
        window.addEventListener('auth:unauthorized', handleUnauthorized)
        return () => window.removeEventListener('auth:unauthorized', handleUnauthorized)
    }, [signOut])

    return <AuthContext.Provider value={{ user, token, signIn, signOut }}>{children}</AuthContext.Provider>
}
function ThemeToggle() { const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark')); const toggle = () => { const next = !dark; setDark(next); document.documentElement.classList.toggle('dark', next); localStorage.setItem('theme', next ? 'dark' : 'light') }; return <button onClick={toggle} className="icon-button" aria-label="Toggle dark mode" title="Toggle dark mode">{dark ? <Sun size={18} /> : <Moon size={18} />}</button> }
function Button({ children, variant = 'primary', className = '', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'ghost' | 'danger' }) { return <button className={`button button-${variant} ${className}`} {...props}>{children}</button> }
function Layout() { const { user, signOut } = useAuth(); const location = useLocation(); const navigate = useNavigate(); const organizer = user?.role === 'organizer'; const nav = organizer ? [['/dashboard', 'Overview', LayoutDashboard], ['/scanner', 'Scanner', ScanLine]] : [['/events', 'Discover', CalendarDays], ['/my-registrations', 'My tickets', Ticket]]; return <div className="min-h-screen bg-paper text-ink transition-colors dark:bg-night dark:text-white"><header className="sticky top-0 z-30 border-b border-line bg-paper dark:border-white/10 dark:bg-night"><div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6"><Link to={organizer ? '/dashboard' : '/events'} className="flex items-center gap-3"><span className="brand-mark"><Ticket size={19} /></span><span className="font-display text-lg font-extrabold tracking-tight">Gather<span className="text-brand-500">in</span></span></Link><nav className="hidden items-center gap-1 md:flex">{nav.map(([href, label, Icon]) => <Link key={href as string} to={href as string} className={`nav-link ${location.pathname.startsWith(href as string) ? 'nav-link-active' : ''}`}><Icon size={16} />{label as string}</Link>)}</nav><div className="flex items-center gap-2"><ThemeToggle /><div className="hidden items-center gap-2 border-l border-line pl-3 sm:flex dark:border-white/10"><CircleUserRound size={18} className="text-muted" /><span className="max-w-28 truncate text-sm font-medium">{user?.full_name || user?.email}</span></div><button className="icon-button" onClick={() => { signOut(); navigate('/login') }} aria-label="Sign out" title="Sign out"><LogOut size={18} /></button></div></div></header><main><Outlet /></main><div className="fixed bottom-4 left-1/2 z-40 flex -translate-x-1/2 gap-1 rounded-2xl border border-line bg-paper p-1.5 shadow-soft md:hidden dark:border-white/10 dark:bg-slate-900">{nav.map(([href, , Icon]) => <Link key={href as string} className="rounded-xl p-3 text-muted hover:bg-brand-50 hover:text-brand-500 dark:hover:bg-white/10" to={href as string}><Icon size={20} /></Link>)}</div></div> }
function AuthPage({ mode }: { mode: 'login' | 'register' }) { const { user, signIn } = useAuth(); const navigate = useNavigate(); const [loading, setLoading] = useState(false); const [error, setError] = useState(''); if (user) return <Navigate to={user.role === 'organizer' ? '/dashboard' : '/events'} replace />; async function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); setLoading(true); setError(''); const data = Object.fromEntries(new FormData(event.currentTarget)); try { const result = await request<{ user: User; access_token: string }>(`/auth/${mode === 'login' ? 'login' : 'register'}`, { method: 'POST', body: JSON.stringify(mode === 'login' ? { email: data.email, password: data.password } : { email: data.email, password: data.password, full_name: data.full_name, role: data.role }) }); signIn(result); navigate(result.user.role === 'organizer' ? '/dashboard' : '/events') } catch (e) { setError((e as Error).message) } finally { setLoading(false) } } return <div className="grid min-h-screen lg:grid-cols-2"><div className="relative hidden bg-ink p-10 text-white lg:flex lg:flex-col lg:justify-between"><div className="flex items-center gap-3"><span className="brand-mark"><Ticket size={19} /></span><span className="font-display text-xl font-bold">Gatherin</span></div><div className="max-w-md"><p className="mb-4 text-sm font-semibold text-brand-300">Events, without the friction</p><h1 className="font-display text-4xl font-bold leading-tight">The room is ready.<br /><span className="text-brand-300">Make it count.</span></h1><p className="mt-5 max-w-md text-base leading-relaxed text-slate-300">A simple place for event teams to welcome people in and keep every moment moving.</p></div><p className="text-sm text-slate-400">A college project for better event check-ins.</p></div><div className="flex items-center justify-center bg-paper px-5 py-12 dark:bg-night"><div className="w-full max-w-md"><div className="mb-10 lg:hidden"><Link to="/login" className="flex items-center gap-3"><span className="brand-mark"><Ticket size={19} /></span><span className="font-display text-lg font-bold">Gatherin</span></Link></div><p className="eyebrow">{mode === 'login' ? 'Welcome back' : 'Start hosting'}</p><h1 className="mt-3 font-display text-3xl font-bold">{mode === 'login' ? 'Good to see you.' : 'Bring your people together.'}</h1><p className="mt-3 text-muted">{mode === 'login' ? 'Sign in to keep your event moving.' : 'Create your free account in under a minute.'}</p><form className="mt-8 space-y-5" onSubmit={submit}>{mode === 'register' && <Field label="Full name" name="full_name" placeholder="Alex Morgan" />}<Field label="Email" name="email" type="email" placeholder="you@example.com" /><Field label="Password" name="password" type="password" placeholder="At least 8 characters" />{mode === 'register' && <label className="field-label">I am joining as<select name="role" className="input mt-2" defaultValue="attendee"><option value="attendee">An attendee</option><option value="organizer">An organizer</option></select></label>}{error && <div className="alert-error">{error}</div>}<Button className="w-full" disabled={loading}>{loading ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}<ChevronRight size={17} /></Button></form><p className="mt-7 text-center text-sm text-muted">{mode === 'login' ? 'New to Gatherin?' : 'Already have an account?'} <Link className="font-bold text-brand-500 hover:underline" to={mode === 'login' ? '/register' : '/login'}>{mode === 'login' ? 'Create one' : 'Sign in'}</Link></p></div></div></div> }
function Field({ label, name, type = 'text', placeholder }: { label: string; name: string; type?: string; placeholder?: string }) { return <label className="field-label">{label}<input className="input mt-2" required name={name} type={type} placeholder={placeholder} /></label> }
function Protected({ role }: { role?: Role }) { const { user } = useAuth(); if (!user) return <Navigate to="/login" replace />; if (role && user.role !== role) return <Navigate to={user.role === 'organizer' ? '/dashboard' : '/events'} replace />; return <Layout /> }
function PageHeader({ eyebrow, title, children }: { eyebrow?: string; title: string; children?: ReactNode }) { return <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1 className="mt-2 font-display text-3xl font-extrabold tracking-tight sm:text-4xl">{title}</h1></div>{children}</div> }
function Stat({ label, value, accent = '' }: { label: string; value: string | number; accent?: string }) { return <div className="stat-card"><span className="text-xs font-bold uppercase tracking-[.14em] text-muted">{label}</span><strong className={`mt-3 block font-display text-3xl font-extrabold ${accent}`}>{value}</strong></div> }
function EventsPage() { const { token } = useAuth(); const [events, setEvents] = useState<EventItem[]>([]); const [loading, setLoading] = useState(true); useEffect(() => { request<{ events: EventItem[] }>('/events', {}, token!).then(data => setEvents(data.events)).catch(e => toast.error(e.message)).finally(() => setLoading(false)) }, [token]); async function register(id: string) { try { await request(`/events/${id}/register`, { method: 'POST', body: '{}' }, token!); toast.success('You are on the list.'); setEvents(events.map(item => item.id === id ? { ...item, registered_count: item.registered_count + 1, spots_remaining: item.spots_remaining - 1 } : item)) } catch (e) { toast.error((e as Error).message) } } return <div className="page-shell"><PageHeader eyebrow="Find your next room" title="Upcoming events"><span className="pill"><CalendarDays size={15} /> {events.length} available</span></PageHeader>{loading ? <Skeletons /> : events.length === 0 ? <Empty icon={<CalendarDays />} title="No events just yet" body="Check back soon. The calendar is still warming up." /> : <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">{events.map(event => <EventCard event={event} key={event.id} onRegister={() => register(event.id)} />)}</div>}</div> }
function EventCard({ event, onRegister }: { event: EventItem; onRegister: () => void }) { const fullness = Math.min(100, event.registered_count / event.capacity * 100); return <article className="surface group flex flex-col justify-between p-5"><div><div className="mb-8 flex items-start justify-between"><span className="date-chip"><CalendarDays size={15} />{new Date(event.event_date).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</span><span className="text-xs font-bold text-muted">{new Date(event.event_date).getFullYear()}</span></div><h2 className="font-display text-xl font-extrabold leading-tight">{event.name}</h2><p className="mt-2 text-sm text-muted">{dateLabel(event.event_date)}</p></div><div className="mt-8"><div className="mb-2 flex justify-between text-xs font-semibold"><span className="text-muted">Capacity</span><span className={event.spots_remaining < 1 ? 'text-coral' : 'text-mint'}>{event.spots_remaining > 0 ? `${event.spots_remaining} spots left` : 'Sold out'}</span></div><div className="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-white/10"><div className={`h-full rounded-full ${event.spots_remaining < 1 ? 'bg-coral' : 'bg-mint'}`} style={{ width: `${fullness}%` }} /></div><Button className="mt-5 w-full" variant={event.spots_remaining < 1 ? 'secondary' : 'primary'} disabled={event.spots_remaining < 1} onClick={onRegister}>{event.spots_remaining < 1 ? 'Event is full' : 'Register now'}{event.spots_remaining > 0 && <ChevronRight size={16} />}</Button></div></article> }
function RegistrationsPage() { const { token } = useAuth(); const [items, setItems] = useState<Registration[]>([]); useEffect(() => { request<{ registrations: Registration[] }>('/registrations', {}, token!).then(data => setItems(data.registrations)).catch(e => toast.error(e.message)) }, [token]); return <div className="page-shell max-w-4xl"><PageHeader eyebrow="Your place is saved" title="My registrations"><span className="pill"><Ticket size={15} /> {items.length} tickets</span></PageHeader>{items.length ? <div className="space-y-4">{items.map(item => <div className="surface flex flex-col gap-5 p-5 sm:flex-row sm:items-center sm:justify-between" key={item.id}><div className="flex items-start gap-4"><span className="number-badge"><Ticket size={18} /></span><div><h2 className="font-display text-lg font-extrabold">{item.event_name}</h2><p className="mt-1 text-sm text-muted">{dateLabel(item.event_date)}</p></div></div><div className="flex items-center gap-3">{item.checked_in_at && <span className="status status-success"><Check size={14} /> Checked in</span>}<Link className="button button-secondary" to={`/registrations/${item.id}/qr`}><QrCode size={16} /> Show QR</Link></div></div>)}</div> : <Empty icon={<Ticket />} title="No registrations yet" body="Find an event that feels like your kind of room." link="/events" linkText="Browse events" />}</div> }
type QrTokenResponse = {
  token: string
  expires_at: string
  session_id: string
  refresh_count: number
  refreshes_remaining: number
}

function QRPage() {
  const { id } = useParams()
  const { token } = useAuth()
  const navigate = useNavigate()

  const [value, setValue] = useState<QrTokenResponse | null>(null)
  const [activeError, setActiveError] = useState<{ code: string; message: string; expires_in: number; expires_at: string } | null>(null)
  const [loading, setLoading] = useState(true)
  const [now, setNow] = useState(() => Date.now())
  const [refreshing, setRefreshing] = useState(false)

  const storageKey = `qr_prev_session_${id}`

  const initialFetchDone = useRef(false)

  const fetchPass = useCallback(async (isAutoRefresh = false) => {
    try {
      setLoading(!isAutoRefresh)
      setRefreshing(isAutoRefresh)

      let path = `/registrations/${id}/qr-token`

      if (isAutoRefresh && value) {
        path += `?session_id=${encodeURIComponent(value.session_id)}&refresh_count=${value.refresh_count + 1}`
      } else {
        const prevSession = sessionStorage.getItem(storageKey)
        if (prevSession) {
          path += `?invalidate_session_id=${encodeURIComponent(prevSession)}`
          sessionStorage.removeItem(storageKey)
        }
      }

      const result = await request<QrTokenResponse>(path, {}, token!)
      setValue(result)
      setActiveError(null)
    } catch (e) {
      const err = e as ApiError
      if (err.status === 409 || err.code === 'TOKEN_ACTIVE') {
        setActiveError({
          code: err.code || 'TOKEN_ACTIVE',
          message: err.message || 'An active QR pass already exists.',
          expires_in: err.expires_in || 60,
          expires_at: err.expires_at || '',
        })
      } else if (err.code === 'SESSION_REFRESH_LIMIT') {
        toast('Maximum auto-refreshes reached. Please request a new code.', { icon: 'ℹ️' })
      } else {
        toast.error((e as Error).message)
      }
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [id, token, value])

  const handleClose = useCallback(() => {
    if (value?.session_id) {
      sessionStorage.setItem(storageKey, value.session_id)
      fetch(`${API}/api/v1/registrations/${id}/qr-token/active`, {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ session_id: value.session_id }),
      }).catch(() => {})
    }
    navigate('/my-registrations')
  }, [value, id, token, navigate])

  useEffect(() => {
    if (initialFetchDone.current) return
    initialFetchDone.current = true
    fetchPass(false)
    return () => {
      if (value?.session_id) {
        sessionStorage.setItem(storageKey, value.session_id)
      }
    }
  }, [id])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const remaining = value ? Math.max(0, Math.ceil((new Date(value.expires_at).getTime() - now) / 1000)) : 0
  const activeErrorRemaining = activeError ? Math.max(0, Math.ceil((new Date(activeError.expires_at).getTime() - now) / 1000)) : 0

  useEffect(() => {
    if (value && remaining <= 5 && remaining > 0 && !refreshing && !loading) {
      if (value.refreshes_remaining > 0) {
        fetchPass(true)
      }
    }
  }, [remaining, value, refreshing, loading, fetchPass])

  useEffect(() => {
    if (value) {
      const canvas = document.getElementById('qr-canvas') as HTMLCanvasElement
      if (canvas) {
        QRCode.toCanvas(canvas, value.token, {
          width: 240,
          margin: 2,
          color: { dark: '#0f172a', light: '#ffffff' },
        })
      }
    }
  }, [value])

  const maxRefreshes = 3
  const totalRounds = maxRefreshes + 1
  const isLastRound = value ? value.refreshes_remaining === 0 : false
  const isExpired = remaining <= 0

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-ink px-5 py-10 text-white">
      <div className="mx-auto max-w-md">
        <button
          onClick={handleClose}
          className="mb-8 inline-flex items-center gap-2 text-sm font-semibold text-slate-300 hover:text-white transition-colors"
        >
          ← Back to tickets
        </button>

        <div className="text-center">
          <p className="eyebrow text-brand-300">Your live pass</p>
          <h1 className="mt-2 font-display text-3xl font-extrabold">Event entry code</h1>
          <p className="mt-2 text-sm text-slate-400">Show this QR code at the door for access.</p>
        </div>

        {activeError ? (
          <div className="mx-auto mt-8 flex max-w-xs flex-col items-center rounded-[2rem] border border-amber-500/30 bg-slate-900/90 p-6 text-center shadow-2xl backdrop-blur-sm">
            <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-500/20 text-amber-400">
              <QrCode size={28} />
            </div>
            <h2 className="font-display text-lg font-bold text-amber-300">QR Pass Already Active</h2>
            <p className="mt-2 text-xs leading-relaxed text-slate-300">
              An active pass is currently open on another device or screen.
            </p>

            <div className="mt-5 flex items-center gap-2 rounded-full bg-amber-500/10 px-4 py-2 text-amber-300 border border-amber-500/20">
              <span className="text-sm">⏱</span>
              <span className="text-xs font-bold">Expires in {activeErrorRemaining > 0 ? activeErrorRemaining : activeError.expires_in}s</span>
            </div>

            <p className="mt-4 text-[11px] text-slate-400">
              Once it expires, you can generate a new pass here.
            </p>

            <button
              onClick={() => fetchPass(false)}
              className="button button-primary mt-6 w-full text-xs font-bold"
            >
              Try generating new pass
            </button>
          </div>
        ) : (
          <div className="mx-auto mt-8 flex max-w-xs flex-col items-center rounded-[2rem] bg-slate-900 border border-white/10 p-6 text-white shadow-2xl">
            {value && (
              <div className="mb-3 flex flex-col items-center gap-1.5">
                <div className="flex items-center gap-1.5">
                  {Array.from({ length: totalRounds }).map((_, i) => (
                    <span
                      key={i}
                      className={`h-2 rounded-full transition-all ${
                        i === value.refresh_count
                          ? 'w-5 bg-brand-400'
                          : i < value.refresh_count
                          ? 'w-2 bg-brand-500/60'
                          : 'w-2 bg-slate-700'
                      }`}
                    />
                  ))}
                </div>
                <span className={`text-[11px] font-semibold ${isLastRound ? 'text-amber-300' : 'text-slate-400'}`}>
                  {isLastRound
                    ? 'Final pass round — manual refresh required after'
                    : `Auto-refreshes ${value.refreshes_remaining} more time${value.refreshes_remaining === 1 ? '' : 's'}`}
                </span>
              </div>
            )}

            <div className="relative mt-2 flex h-[260px] w-[260px] items-center justify-center rounded-2xl bg-white p-3 shadow-inner">
              {loading ? (
                <div className="flex flex-col items-center gap-2 text-slate-600">
                  <div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" />
                  <span className="text-xs font-semibold">Generating QR…</span>
                </div>
              ) : isExpired && isLastRound ? (
                <div className="flex flex-col items-center p-4 text-center text-slate-800">
                  <span className="text-3xl">⌛</span>
                  <span className="mt-2 text-sm font-bold text-amber-600">Session Completed</span>
                  <span className="mt-1 text-xs text-slate-500">Tap button below to request a fresh QR code.</span>
                </div>
              ) : (
                <canvas id="qr-canvas" width="240" height="240" />
              )}
            </div>

            {value && !loading && (
              <div className="mt-5 flex flex-col items-center w-full">
                <div
                  className={`flex items-center gap-2 rounded-full px-4 py-1.5 text-xs font-bold transition-colors ${
                    isExpired
                      ? 'bg-red-500/20 text-red-300 border border-red-500/30'
                      : remaining <= 10
                      ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30 animate-pulse'
                      : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                  }`}
                >
                  <span>⏱</span>
                  <span>
                    {isExpired
                      ? 'Pass Expired'
                      : isLastRound
                      ? `Expires in ${remaining}s (Last pass)`
                      : `Refreshes in ${remaining}s`}
                  </span>
                </div>

                {isExpired && (
                  <button
                    onClick={() => fetchPass(false)}
                    className="button button-primary mt-4 w-full text-xs font-bold"
                  >
                    Request New QR Pass
                  </button>
                )}
              </div>
            )}

            <button
              onClick={handleClose}
              className="button button-secondary mt-5 w-full text-xs font-semibold text-slate-300"
            >
              Close Pass & Invalidate
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
function OrganizerDashboard() { const { token } = useAuth(); const [events, setEvents] = useState<EventItem[]>([]); useEffect(() => { request<{ events: EventItem[] }>('/events', {}, token!).then(data => setEvents(data.events)).catch(e => toast.error(e.message)) }, [token]); return <div className="page-shell"><PageHeader eyebrow="Your command center" title="Your events"><Link className="button button-primary" to="/dashboard/events/new"><Plus size={17} /> Create event</Link></PageHeader>{events.length ? <div className="surface overflow-hidden"><div className="hidden grid-cols-[1.5fr_1fr_1fr_auto] gap-4 border-b border-line px-5 py-4 text-xs font-bold uppercase tracking-widest text-muted md:grid"><span>Event</span><span>Date</span><span>Attendance</span><span /></div>{events.map(event => <div className="grid gap-4 border-b border-line px-5 py-5 last:border-0 md:grid-cols-[1.5fr_1fr_1fr_auto] md:items-center dark:border-white/10" key={event.id}><div><h2 className="font-display font-bold">{event.name}</h2><p className="mt-1 text-sm text-muted">{event.spots_remaining} spots remaining</p></div><p className="text-sm text-muted">{dateLabel(event.event_date)}</p><div><p className="font-bold">{event.registered_count} <span className="font-normal text-muted">/ {event.capacity}</span></p><div className="mt-2 h-1.5 w-28 rounded-full bg-slate-100 dark:bg-white/10"><div className="h-full rounded-full bg-brand-500" style={{ width: `${event.registered_count / event.capacity * 100}%` }} /></div></div><Link className="button button-secondary" to={`/dashboard/events/${event.id}`}>View live <ChevronRight size={15} /></Link></div>)}</div> : <Empty icon={<LayoutDashboard />} title="Your calendar is clear" body="Create an event and give people somewhere worth showing up." link="/dashboard/events/new" linkText="Create your first event" />}</div> }
function CreateEvent({ edit = false }: { edit?: boolean }) { const { id } = useParams(); const { token } = useAuth(); const navigate = useNavigate(); const [initial, setInitial] = useState<EventItem | null>(null); const [loading, setLoading] = useState(false); useEffect(() => { if (edit && id) request<{ event: EventItem }>(`/events/${id}`, {}, token!).then(d => setInitial(d.event)).catch(e => toast.error(e.message)) }, [edit, id, token]); async function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); setLoading(true); const data = Object.fromEntries(new FormData(event.currentTarget)); try { const body = { name: data.name, event_date: new Date(data.event_date as string).toISOString(), capacity: Number(data.capacity) }; const result = await request<{ event: EventItem }>(edit ? `/events/${id}` : '/events', { method: edit ? 'PATCH' : 'POST', body: JSON.stringify(body) }, token!); toast.success(edit ? 'Event updated.' : 'Event created.'); navigate(`/dashboard/events/${result.event.id}`) } catch (e) { toast.error((e as Error).message) } finally { setLoading(false) } } return <div className="page-shell max-w-2xl"><Link to={edit ? `/dashboard/events/${id}` : '/dashboard'} className="back-link">← {edit ? 'Back to event' : 'Your events'}</Link><PageHeader eyebrow={edit ? 'Make a change' : 'Set the room'} title={edit ? 'Edit event' : 'Create an event'} /><form onSubmit={submit} className="surface space-y-6 p-6 sm:p-8"><Field label="Event name" name="name" placeholder="A night worth remembering" /><label className="field-label">Date & time<input required className="input mt-2" type="datetime-local" name="event_date" defaultValue={initial ? toDateInput(initial.event_date) : ''} /></label><label className="field-label">Capacity<input required className="input mt-2" type="number" min={initial?.registered_count || 1} name="capacity" defaultValue={initial?.capacity || ''} /><span className="mt-2 block text-xs font-normal text-muted">Set the maximum number of people you can welcome.</span></label><div className="flex flex-col-reverse gap-3 pt-3 sm:flex-row sm:justify-end"><Link className="button button-ghost justify-center" to={edit ? `/dashboard/events/${id}` : '/dashboard'}>Cancel</Link><Button disabled={loading}>{loading ? 'Saving…' : edit ? 'Save changes' : 'Create event'}<ChevronRight size={16} /></Button></div></form></div> }
function LiveDashboard() {
  const { id } = useParams()
  const { token } = useAuth()
  const [data, setData] = useState<Dashboard | null>(null)
  const [socketConnected, setSocketConnected] = useState(true)
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)
  const [thinkingText, setThinkingText] = useState('Ask Gatherin')
  const [answer, setAnswer] = useState<{ answer: string | null; raw_stats: any; fallback: boolean } | null>(null)
  const [search, setSearch] = useState('')

  useEffect(() => {
    request<Dashboard>(`/events/${id}/dashboard`, {}, token!).then(setData).catch((e) => toast.error(e.message))
    const socket = io(API || window.location.origin, { auth: { token } })

    socket.on('connect', () => setSocketConnected(true))
    socket.on('disconnect', () => setSocketConnected(false))

    socket.on('checkin:new', (checkin: { registration_id: string; checked_in_at: string; checked_in_count?: number; spots_remaining?: number }) =>
      setData((current) =>
        current
          ? {
              ...current,
              checked_in_count: checkin.checked_in_count ?? (current.checked_in_count + 1),
              spots_remaining: checkin.spots_remaining ?? (current.capacity - (current.checked_in_count + 1)),
              attendees: current.attendees.map((item) =>
                item.registration_id === checkin.registration_id ? { ...item, checked_in_at: checkin.checked_in_at } : item
              ),
            }
          : current
      )
    )

    socket.on('event:stats_update', (stats: { registered_count?: number; spots_remaining?: number }) =>
      setData((current) =>
        current
          ? {
              ...current,
              registered_count: stats.registered_count ?? current.registered_count,
              spots_remaining: stats.spots_remaining ?? current.spots_remaining,
            }
          : current
      )
    )

    return () => {
      socket.disconnect()
    }
  }, [id, token])

  async function ask(event: FormEvent) {
    event.preventDefault()
    if (!question.trim() || loading) return
    setLoading(true)
    setThinkingText('Thinking…')

    const timer = setTimeout(() => {
      setThinkingText('Still thinking…')
    }, 3000)

    try {
      const res = await request<{ answer: string | null; raw_stats: any; fallback: boolean }>(
        `/events/${id}/ai-query`,
        { method: 'POST', body: JSON.stringify({ question: question.trim() }) },
        token!
      )
      setAnswer(res)
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      clearTimeout(timer)
      setLoading(false)
    }
  }

  const presetQuestions = [
    'How many people have checked in so far?',
    'What percentage of registered attendees are no-shows?',
    'What time did check-ins peak?',
    'How many spots are left?',
  ]

  const attendees = useMemo(
    () => data?.attendees.filter((item) => item.name.toLowerCase().includes(search.toLowerCase())) || [],
    [data, search]
  )

  if (!data)
    return (
      <div className="page-shell">
        <Skeletons />
      </div>
    )

  return (
    <div className="page-shell max-w-7xl">
      <Link to="/dashboard" className="back-link">
        ← Your events
      </Link>

      {!socketConnected && (
        <div className="mb-4 rounded-xl bg-amber-500/15 border border-amber-500/30 p-3 text-amber-200 text-xs font-semibold">
          Live updates paused — reconnecting to server…
        </div>
      )}

      <PageHeader eyebrow="Live room view" title="Event dashboard">
        <div className="flex gap-2">
          <a id="export-csv" className="button button-secondary" href={`${API}/api/v1/events/${id}/export`}>
            <Download size={16} /> Export CSV
          </a>
          <Link className="button button-ghost" to={`/dashboard/events/${id}/edit`}>
            Edit
          </Link>
        </div>
      </PageHeader>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Capacity" value={data.capacity} />
        <Stat label="Registered" value={data.registered_count} accent="text-brand-500" />
        <Stat label="Checked in" value={data.checked_in_count} accent="text-mint" />
        <Stat label="Spots left" value={data.spots_remaining} accent="text-coral" />
      </div>
      <div className="mt-6 grid gap-6 lg:grid-cols-[1.5fr_.8fr]">
        <section className="surface p-5">
          <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 className="font-display text-xl font-extrabold">Guest list</h2>
              <p className="mt-1 text-sm text-muted">Every arrival, in one view.</p>
            </div>
            <label className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" size={16} />
              <input
                id="attendee-search"
                className="input pl-9"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search guests by name…"
              />
            </label>
          </div>
          <div className="divide-y divide-line dark:divide-white/10">
            {attendees.map((item) => (
              <div className="flex items-center justify-between gap-4 py-4" key={item.registration_id}>
                <div className="flex items-center gap-3">
                  <span className="avatar">{item.name.slice(0, 1)}</span>
                  <span className="font-semibold">{item.name}</span>
                </div>
                {item.checked_in_at ? (
                  <span className="status status-success">
                    <Check size={14} /> {new Date(item.checked_in_at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
                  </span>
                ) : (
                  <span className="text-sm text-muted">— Not checked in</span>
                )}
              </div>
            ))}
          </div>
        </section>
        <aside className="surface h-fit p-5">
          <p className="eyebrow">Event intelligence</p>
          <h2 className="mt-2 font-display text-xl font-extrabold">Ask about your room</h2>
          <div className="mt-5 flex flex-wrap gap-2">
            {presetQuestions.map((q) => (
              <button key={q} onClick={() => setQuestion(q)} className="chip text-xs">
                {q}
              </button>
            ))}
          </div>
          <form className="mt-5 space-y-3" onSubmit={ask}>
            <textarea
              id="ai-question"
              className="input min-h-24 resize-none"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="Ask about this event…"
            />
            <Button id="ai-submit" className="w-full" disabled={loading || !question.trim()}>
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  {thinkingText}
                </span>
              ) : (
                <>
                  Ask Gatherin <ChevronRight size={16} />
                </>
              )}
            </Button>
          </form>
          {answer && (
            <div id="ai-answer" className="mt-5 rounded-2xl bg-brand-50 p-4 text-sm leading-relaxed text-ink dark:bg-brand-500/10 dark:text-slate-200">
              {answer.fallback && (
                <p className="text-xs text-muted mb-2 font-medium">
                  ℹ️ Showing computed stats (AI summary unavailable)
                </p>
              )}
              <p className="font-medium text-slate-900 dark:text-slate-100">{answer.answer}</p>
              <details className="mt-3 text-xs text-muted">
                <summary className="cursor-pointer font-bold text-brand-600 dark:text-brand-300 hover:underline">
                  View raw stats
                </summary>
                <pre className="mt-2 p-2 bg-slate-900 text-slate-200 text-xs rounded-lg overflow-x-auto">
                  {JSON.stringify(answer.raw_stats, null, 2)}
                </pre>
              </details>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}

function ScannerWrapper() {
  const { token } = useAuth()
  return <CameraScanner token={token || ''} />
}

function Empty({ icon, title, body, link, linkText }: { icon: ReactNode; title: string; body: string; link?: string; linkText?: string }) { return <div className="surface flex flex-col items-center px-6 py-16 text-center"><span className="number-badge mb-5">{icon}</span><h2 className="font-display text-xl font-extrabold">{title}</h2><p className="mt-2 max-w-sm text-sm text-muted">{body}</p>{link && <Link className="button button-primary mt-6" to={link}>{linkText} <ChevronRight size={16} /></Link>}</div> }
function Skeletons() { return <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">{[1, 2, 3].map(i => <div className="surface h-64 animate-pulse" key={i} />)}</div> }
function NotFound() { return <div className="page-shell text-center"><p className="eyebrow">404</p><h1 className="mt-3 font-display text-4xl font-extrabold">This room does not exist.</h1><Link className="button button-primary mt-6" to="/">Go home</Link></div> }
function Home() { const { user } = useAuth(); return <Navigate to={user?.role === 'organizer' ? '/dashboard' : '/events'} replace /> }
export default function App() { return <BrowserRouter><AuthProvider><Routes><Route path="/login" element={<AuthPage mode="login" />} /><Route path="/register" element={<AuthPage mode="register" />} /><Route element={<Protected />}><Route path="/" element={<Home />} /></Route><Route element={<Protected role="attendee" />}><Route path="/events" element={<EventsPage />} /><Route path="/my-registrations" element={<RegistrationsPage />} /><Route path="/registrations/:id/qr" element={<QRPage />} /></Route><Route element={<Protected role="organizer" />}><Route path="/dashboard" element={<OrganizerDashboard />} /><Route path="/dashboard/events/new" element={<CreateEvent />} /><Route path="/dashboard/events/:id" element={<LiveDashboard />} /><Route path="/dashboard/events/:id/edit" element={<CreateEvent edit />} /><Route path="/scanner" element={<ScannerWrapper />} /></Route><Route path="*" element={<NotFound />} /></Routes><Toaster position="bottom-right" /></AuthProvider></BrowserRouter> }
